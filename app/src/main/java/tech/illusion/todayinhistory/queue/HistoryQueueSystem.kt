package tech.illusion.todayinhistory.queue

import com.pico.spatial.core.ecs.EntityQueryCondition
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.illusion.todayinhistory.data.HistoryEntry

/** 跨午夜检查的节流间隔（秒）。 */
private const val DAY_CHECK_INTERVAL_SECONDS = 1f

/**
 * 一次重载请求在多久之后被视为失败并允许重试（秒）。
 *
 * 没有这个上界的话，只要有一次装载抛异常（读盘失败、JSON 损坏、协程被取消），
 * in-flight 标志就永远挂着，`onDayChanged` 再也不会被触发——首次冷启动时表现为
 * 「一片空白且永不恢复」，而且没有任何日志能指向这里。
 */
private const val RELOAD_TIMEOUT_SECONDS = 10f

/**
 * System 与 Compose / 宿主 Composable 之间的桥。
 *
 * System 单向往这里写快照，Compose 单向读。反方向只有两条极窄的通道，
 * 都是「投递数据、由 System 在下一帧的场景线程上消费」，**没有任何一条会让外部线程
 * 直接碰 [QueueDriver] 或 ECS API**：
 *
 * - [onCardClicked]：把用户点击排进队列；
 * - [submitReload]：把协程里读好的新一天数据投递进来，由 System 调 `driver.reset(...)`。
 *
 * 后者是刻意的。`QueueDriver` 非线程安全（见其 Task 5 交接第 14 条），而 `Scene` /
 * `Entity` / `Component` 全部标了 `@MainThread`。让 IO 协程直接调 `reset` 会同时踩两条线。
 *
 * 用 StateFlow 而不是 `MutableState`：System.update 虽然跑在主线程，
 * 但 StateFlow 的线程安全保证让这条边界不依赖那个假设。
 */
object QueueHost {

    /** 一次待落地的重载：由协程投递，由 System 在场景线程上应用。 */
    class PendingReload(val entries: List<HistoryEntry>, val month: Int, val day: Int)

    private val _snapshots = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshots: StateFlow<QueueSnapshot> = _snapshots.asStateFlow()

    /** 由 HomeVolume 在容器建立时注入。 */
    @Volatile var driver: QueueDriver? = null

    /**
     * SpatialView 包围盒尺寸（米），由 HomeVolume 在 `onSizeChanged` 时更新。
     *
     * 默认值 `Vector3(1f, 1f, 1f)` 只是占位——第一次 `onSizeChanged` 之前按它算出来的
     * 位置是错的。这正是日期牌也必须走 [HistoryQueueSystem] 而不能在 `initial` 回调里
     * 一次性摆好的原因（见 [DatePanelComponent]）。
     */
    @Volatile var boundsMeters: Vector3 = Vector3(1f, 1f, 1f)

    /** 待消费的点击请求：前进多少条。0 表示没有待处理请求。 */
    @Volatile private var pendingStep: Int = 0

    @Volatile private var pendingReload: PendingReload? = null

    /**
     * System 检测到「快照装的那天已经过去」时触发，请求宿主去读新一天的数据。
     *
     * 实现方**必须是非阻塞的**（起个协程就返回），它在场景线程上被调用。
     * 数据读好之后调 [submitReload] 投递回来，不要自己去碰 [driver]。
     *
     * System 侧已经做了两层保护：秒级节流 + in-flight 去重（见 [HistoryQueueSystem]），
     * 所以本回调不会每帧被打一次。
     */
    @Volatile var onDayChanged: (() -> Unit)? = null

    fun onCardClicked(slot: Int) {
        // 点焦点卡（slot 0）也前进一条；点第 i 条待读直接跳 i 条。
        pendingStep = if (slot <= 0) 1 else slot
    }

    /**
     * 投递一批新装载好的数据。可从任意线程调用。
     *
     * 后到的覆盖先到的：同一天的数据装两遍没有意义，取最新的即可。
     */
    fun submitReload(entries: List<HistoryEntry>, month: Int, day: Int) {
        pendingReload = PendingReload(entries, month, day)
    }

    internal fun consumePendingStep(): Int {
        val step = pendingStep
        pendingStep = 0
        return step
    }

    internal fun consumePendingReload(): PendingReload? {
        val reload = pendingReload
        pendingReload = null
        return reload
    }

    internal fun publish(snapshot: QueueSnapshot) {
        _snapshots.value = snapshot
    }

    /** 容器销毁时清空，避免持有过期的 driver / 回调。 */
    fun clear() {
        driver = null
        onDayChanged = null
        pendingStep = 0
        pendingReload = null
        boundsMeters = Vector3(1f, 1f, 1f)
        _snapshots.value = QueueSnapshot.EMPTY
    }
}

/**
 * 每帧驱动队列：落地重载、消费点击、推进计时、把插值出来的姿态写进 `TransformComponent`。
 *
 * 严格约束（SDK 要求，见 `System.update` 的 KDoc：*"You must avoid blocking current callback
 * thread or exchanging other asynchronous threads to execute ECS APIs."*）：
 * 本方法跑在场景线程上，**不得阻塞、不得起协程、不得跨线程调 ECS API**。
 * 因此跨午夜的重新装载在这里只**发一个信号**（[QueueHost.onDayChanged]），
 * 实际的 IO 由宿主侧的协程完成，读好之后经 [QueueHost.submitReload] 投递回来，
 * 由本 System 在下一帧的场景线程上调用 `driver.reset(...)` 落地。
 *
 * `System` 的子类必须提供 public 无参构造（`System` 的 KDoc：*"Subclasses of System must
 * provide a public no-argument constructor to make internal mechanism work."*）。本类满足。
 *
 * 注册/注销由宿主 Composable 用 `registerSystem<HistoryQueueSystem>()` /
 * `unregisterSystem<HistoryQueueSystem>()` 在 `DisposableEffect` 里完成（Task 7/8）。
 */
class HistoryQueueSystem : System() {

    private var lastPublished: QueueSnapshot? = null

    /** 距上次跨午夜检查累计了多少秒。 */
    private var sinceDayCheck = 0f

    /** 已经发出重载请求、尚未等到 [QueueHost.submitReload] 落地。 */
    private var reloadInFlight = false

    /** 当前这次 in-flight 已经等了多久，用于 [RELOAD_TIMEOUT_SECONDS] 超时重试。 */
    private var reloadWaitSeconds = 0f

    override fun update(context: SceneUpdateContext) {
        super.update(context)

        val driver = QueueHost.driver
        if (driver == null) {
            // 容器还没建立或已经销毁。把本实例的状态清干净，重新挂上时才不会带着
            // 上一轮的 in-flight 标志和 lastPublished 引用。
            resetLocalState()
            return
        }

        val delta = context.deltaTime

        // 1. 先落地宿主投递回来的新数据。reset 会打断进行中的过渡并让姿态瞬间归位
        //    （Task 5 交接第 8 条），所以放在 tick 之前，本帧算出来的就是新数据的姿态。
        val reload = QueueHost.consumePendingReload()
        if (reload != null) {
            driver.reset(reload.entries, reload.month, reload.day)
            reloadInFlight = false
            reloadWaitSeconds = 0f
            sinceDayCheck = 0f
        }

        // 2. 消费点击。过渡进行中 requestAdvance 会被 driver 静默忽略（Task 5 交接第 4 条），
        //    这里不补排队——「过渡中的点击丢掉」是当前刻意的手感，要改属于 Task 9 的决策。
        val step = QueueHost.consumePendingStep()
        if (step > 0) {
            driver.requestAdvance(step)
        }

        // 3. 推进计时。顺序按 Task 5 交接第 2 条：先 tick，再 poseOf，再推 snapshot。
        driver.tick(delta)

        checkDayChange(driver, delta)

        // 4. 姿态写进 TransformComponent（3D 侧，不经过 Compose）。
        applyPoses(context, driver)

        // 5. 快照推给 Compose（2D 侧）。QueueDriver 每次 publish 都新建一个 QueueSnapshot，
        //    所以引用比较足以判定「这一帧内容变了没有」，不必做 equals 深比较。
        val snapshot = driver.snapshot
        if (snapshot !== lastPublished) {
            lastPublished = snapshot
            QueueHost.publish(snapshot)
        }
    }

    /**
     * 跨午夜检查，带两层保护。缺任何一层都会出问题：
     *
     * - **秒级节流**：`dayChanged()` 每次调用都走一遍 `LocalDate.now()`，含时区规则查询与
     *   对象分配（Task 5 交接第 7 条）。跨午夜的检测精度用不着每帧，1 秒足够。
     * - **in-flight 去重**：`dayChanged()` 是**电平信号不是边沿信号**（Task 5 交接第 5 条）
     *   ——从「已发起加载」到「`reset` 落地」的整个窗口里它持续为 true。不去重的话，
     *   异步读盘+解析期间每次检查都会再发起一次加载，而每次 `reset` 都会重新随机洗牌并
     *   清零计时，队列会在启动瞬间反复跳变。注意这不是只在跨午夜才出现的边角情况：
     *   首次 `reset` 之前快照是哨兵 `0/0`，`dayChanged()` 恒为 true，**每次冷启动必现**。
     *
     * 超时重试见 [RELOAD_TIMEOUT_SECONDS]。
     */
    private fun checkDayChange(driver: QueueDriver, delta: Float) {
        if (reloadInFlight) {
            reloadWaitSeconds += delta
            if (reloadWaitSeconds < RELOAD_TIMEOUT_SECONDS) return
            // 等太久了，当作这次装载失败，放开去重标志允许下一轮重试。
            reloadInFlight = false
            reloadWaitSeconds = 0f
        }

        sinceDayCheck += delta
        if (sinceDayCheck < DAY_CHECK_INTERVAL_SECONDS) return
        sinceDayCheck = 0f

        if (!driver.dayChanged()) return

        val callback = QueueHost.onDayChanged ?: return
        reloadInFlight = true
        reloadWaitSeconds = 0f
        callback.invoke()
    }

    /**
     * 把每个槽位当前的姿态写进对应实体的 `TransformComponent`。
     *
     * 姿态是**相对包围盒的比例因子**（见 `SlotPose`），乘上 [QueueHost.boundsMeters] 才是米。
     * X 恒为 0：卡片沿深度方向排队，不做横向偏移。
     *
     * ### 为什么这里不写 alpha
     *
     * `SlotPose.alpha` 是「越远越淡」那条视觉规则，**由面板 Composable 按槽位静态应用**
     * （`Modifier.graphicsLayer { alpha = SlotLayout.poseFor(slot).alpha }`，Task 7），
     * 不在本方法里逐帧写。两个理由：
     *
     * 1. 实体级不透明度需要另一套 API（材质/渲染组件），`TransformComponent` 没有这个字段——
     *    在这里写 alpha 意味着把整套材质操作引进每帧路径；
     * 2. 每张卡的**静止**alpha 只取决于它的槽位号，而槽位号在实体的整个生命周期里不变
     *    （推进换的是槽位里的内容，不是实体与槽位的绑定），所以它根本不需要每帧重算。
     *
     * 代价是过渡的 0.6 秒里 alpha 不跟着插值——卡片会在滑动的同时**保持各自槽位的**
     * 不透明度，而不是渐变过去。这是已知的取舍，不是漏实现。要不要补属于 Task 9 的视觉决策。
     *
     * ### 已知的端点退化（Task 5 交接第 11 条，非本任务处理）
     *
     * `poseOf` 的起点会被 `SlotLayout.poseFor` 钳制，于是：队尾新进的卡起点与目标相同，
     * **原地不动**没有滑入；队头被挤出去的卡不在新快照里，是**瞬间消失**没有淡出。
     * 实际观感是「中间几张滑，两端硬切」。这是 6 槽固定窗口模型的必然结果，
     * 要补得另做入场/出场淡入淡出或多渲染一张队尾缓冲卡——属 Task 7/9 的视觉决策。
     */
    private fun applyPoses(context: SceneUpdateContext, driver: QueueDriver) {
        val bounds = QueueHost.boundsMeters
        val scene = context.scene

        val cardCondition = EntityQueryCondition.hasComponent(HistoryCardComponent::class.java)
        scene.queryEntity(cardCondition).forEach { entity ->
            val card = entity.components[HistoryCardComponent::class.java] ?: return@forEach
            val transform = entity.components[TransformComponent::class.java] ?: return@forEach
            applyPose(transform, driver.poseOf(card.slot), bounds)
        }

        // 日期牌姿态恒定，但仍然每帧写：boundsMeters 会随容器缩放变化，而 System 侧
        // 收不到任何变更通知。一个实体的写入开销可以忽略，换来「缩放后自动跟随」。
        val dateCondition = EntityQueryCondition.hasComponent(DatePanelComponent::class.java)
        scene.queryEntity(dateCondition).forEach { entity ->
            val transform = entity.components[TransformComponent::class.java] ?: return@forEach
            applyPose(transform, SlotLayout.datePose, bounds)
        }
    }

    private fun applyPose(transform: TransformComponent, pose: SlotPose, bounds: Vector3) {
        transform.position = Vector3(0f, pose.yFactor * bounds.y, pose.zFactor * bounds.z)
        transform.scaleVector = Vector3(pose.scale, pose.scale, pose.scale)
    }

    private fun resetLocalState() {
        lastPublished = null
        sinceDayCheck = 0f
        reloadInFlight = false
        reloadWaitSeconds = 0f
    }
}
