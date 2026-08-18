package tech.illusion.todayinhistory.queue

import android.util.Log
import com.pico.spatial.core.ecs.EntityQueryCondition
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.illusion.todayinhistory.data.HistoryEntry

private const val LOG_TAG = "HistoryQueueSystem"

/** 跨午夜检查的节流间隔（秒）。 */
private const val DAY_CHECK_INTERVAL_SECONDS = 1f

/**
 * in-flight 标志的**纯兜底**超时（秒）。正常路径永远不该走到这里。
 *
 * 正常的结束方式有且只有两种，都由宿主协程显式回信号（见 [QueueHost.submitReload] /
 * [QueueHost.reloadFailed]）：装载成功 → `Loaded`，装载失败 → `Failed`。
 * 两者都会立刻清掉 in-flight 标志，不必等超时。
 *
 * 本常量只负责一种情况：**协程既没成功也没失败地消失了**——被取消、线程被杀、
 * 或者宿主根本忘了回信号。没有它，标志就永远挂着，`onDayChanged` 再也不会被触发，
 * 冷启动表现为「一片空白且永不恢复」，且没有任何线索指向这里。
 *
 * 取 30f 而不是更短，是因为它**不再承担「装载太慢」的判定职责**了。
 * 早先版本取 10f 并兼任慢速判定，那是个设计缺陷：冷启动读盘+解析在低端设备上
 * （首次 JIT、主线程被 Compose 首帧挤占）完全可能超过 10 秒，超时会放开去重标志，
 * 1 秒后发出第二次 `onDayChanged`，两个协程各自 `submitReload`，System 先后落地两次
 * `reset`——**第二次重新随机洗牌，把已经渲染出来的那条内容换掉**。
 * 现在慢速由「等信号」处理（等多久都行），超时只兜真正的失联。
 */
private const val RELOAD_TIMEOUT_SECONDS = 30f

/**
 * 单帧 delta 计入 in-flight 超时预算的上限（秒）。
 *
 * `deltaTime` 在 pause 恢复、首帧、或一次长 GC 之后可能是秒级甚至十秒级。
 * 不设上限的话，一帧就能吃掉整个超时预算并误判失联——而这恰恰最容易发生在
 * **冷启动装载窗口**里，也就是最不该误判的时刻。
 *
 * 只用于 [RELOAD_TIMEOUT_SECONDS] 的累计。`QueueDriver.tick` 拿到的是**未截断的**
 * delta：那边的 5 分钟自动推进计的是真实流逝时间，截断会让它变慢。
 */
private const val MAX_TIMEOUT_BUDGET_DELTA_SECONDS = 0.5f

/**
 * System 与 Compose / 宿主 Composable 之间的桥。
 *
 * System 单向往这里写快照，Compose 单向读。反方向只有两条极窄的通道，
 * 都是「投递数据、由 System 在下一帧的场景线程上消费」，**没有任何一条会让外部线程
 * 直接碰 [QueueDriver] 或 ECS API**：
 *
 * - [onCardClicked]：把用户点击排进队列；
 * - [submitReload] / [reloadFailed]：把协程装载的**结果**（成功或失败）投递进来，
 *   由 System 在场景线程上处理。
 *
 * 后者是刻意的。`QueueDriver` 非线程安全（见其 Task 5 交接第 14 条），而 `Scene` /
 * `Entity` / `Component` 全部标了 `@MainThread`。让 IO 协程直接调 `reset` 会同时踩两条线。
 *
 * 跨线程传的只有**不可变载荷**，且都走 `Atomic*` / `@Volatile`，由此建立 happens-before。
 *
 * 用 StateFlow 而不是 `MutableState`：System.update 虽然跑在主线程，
 * 但 StateFlow 的线程安全保证让这条边界不依赖那个假设。
 */
object QueueHost {

    /**
     * 一次装载尝试的结果。成功与失败共用**同一个单槽通道**，天然是「后到的覆盖先到的」，
     * 不存在「先读到失败、又读到成功」这类需要额外定序的情况。
     */
    sealed class ReloadResult {

        /** 装载成功，带回可以直接交给 `QueueDriver.reset` 的载荷。 */
        class Loaded(val entries: List<HistoryEntry>, val month: Int, val day: Int) : ReloadResult()

        /** 装载失败。System 会清掉 in-flight 标志并在下一个节流窗口重试。 */
        object Failed : ReloadResult()
    }

    private val _snapshots = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshots: StateFlow<QueueSnapshot> = _snapshots.asStateFlow()

    /** 由 HomeVolume 在容器建立时注入。 */
    @Volatile var driver: QueueDriver? = null

    /**
     * SpatialView 包围盒尺寸（米），由 HomeVolume 更新。
     *
     * 默认值 `Vector3(1f, 1f, 1f)` 只是占位——第一次更新之前按它算出来的位置是错的。
     * 这正是日期牌也必须走 [HistoryQueueSystem] 而不能在 `initial` 回调里一次性摆好的
     * 原因（见 [DatePanelComponent]）。
     *
     * ### z 从哪来（Task 8 必读，否则会去找一个不存在的 API）
     *
     * 1. **z 不来自 `Modifier.onSizeChanged`。** 那个 API 只给 width/height 两维像素，
     *    官方的 px→米 换算范例也只转这两维。**不要去找第三维的回调，没有。**
     * 2. 当前约定是 **`z = minOf(widthMeters, heightMeters)`**。依据是容器在 Manifest 里
     *    被声明为 1000×1000×1000 dp 的**立方体**，深度与较短的那条边同量级是合理起点。
     * 3. **这是代理值，不是实测值。** `applyPose` 的深度完全由 `pose.zFactor * bounds.z`
     *    决定，而 zFactor 跨度是 0.30 → -0.42，是本应用的核心视觉。Task 9 调优时若觉得
     *    卡片的**深度间距**观感不对（太挤或太散），**第一个该怀疑的就是这个代理值**，
     *    而不是去改 `SlotLayout` 的 zFactor 表。
     */
    @Volatile var boundsMeters: Vector3 = Vector3(1f, 1f, 1f)

    /**
     * 待消费的点击请求：前进多少条。0 表示没有待处理请求。
     *
     * 用 `AtomicInteger` 而不是 `@Volatile Int`：消费端是「读出来再清零」的读-改-写，
     * `@Volatile` 保证不了原子性——System 读到之后、写回 0 之前若另一线程写入新值，
     * 那次点击会被静默吞掉。`getAndSet` 没有这个缺口，且零成本。
     */
    private val pendingStep = AtomicInteger(0)

    /** 同上，用 `AtomicReference.getAndSet(null)` 消除 lost update。 */
    private val pendingReload = AtomicReference<ReloadResult?>(null)

    /**
     * System 检测到「快照装的那天已经过去」时触发，请求宿主去读新一天的数据。
     *
     * 实现方**必须是非阻塞的**（起个协程就返回），它在场景线程上被调用。
     *
     * **无论成功还是失败都必须回信号**：成功调 [submitReload]，失败（含异常、取消）
     * 调 [reloadFailed]。不回信号会退化成靠 [RELOAD_TIMEOUT_SECONDS] 兜底，
     * 白白多等 30 秒。不要自己去碰 [driver]。
     *
     * System 侧已经做了节流 + in-flight 去重，本回调不会每帧被打一次。
     */
    @Volatile var onDayChanged: (() -> Unit)? = null

    fun onCardClicked(slot: Int) {
        // 点焦点卡（slot 0）也前进一条；点第 i 条待读直接跳 i 条。
        pendingStep.set(if (slot <= 0) 1 else slot)
    }

    /**
     * 投递一批装载好的数据。可从任意线程调用。
     *
     * 后到的覆盖先到的：同一天的数据装两遍没有意义，取最新的即可。
     */
    fun submitReload(entries: List<HistoryEntry>, month: Int, day: Int) {
        pendingReload.set(ReloadResult.Loaded(entries, month, day))
    }

    /**
     * 报告本次装载失败。可从任意线程调用。
     *
     * System 收到后立刻清掉 in-flight 标志，并在下一个节流窗口（约 1 秒后）重试，
     * 而不是干等 [RELOAD_TIMEOUT_SECONDS]。持续失败时退化为每秒重试一次，不会热循环。
     */
    fun reloadFailed() {
        pendingReload.set(ReloadResult.Failed)
    }

    internal fun consumePendingStep(): Int = pendingStep.getAndSet(0)

    internal fun consumePendingReload(): ReloadResult? = pendingReload.getAndSet(null)

    internal fun publish(snapshot: QueueSnapshot) {
        _snapshots.value = snapshot
    }

    /** 容器销毁时清空，避免持有过期的 driver / 回调。 */
    fun clear() {
        driver = null
        onDayChanged = null
        pendingStep.set(0)
        pendingReload.set(null)
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
 * 实际的 IO 由宿主侧的协程完成，结果经 [QueueHost.submitReload] / [QueueHost.reloadFailed]
 * 投递回来，由本 System 在下一帧的场景线程上处理。
 *
 * `System` 的子类必须提供 public 无参构造（`System` 的 KDoc：*"Subclasses of System must
 * provide a public no-argument constructor to make internal mechanism work."*）。本类满足。
 *
 * ### 前提：每帧只被调用一次（**开第二个容器前必须回来看这里**）
 *
 * 本类把 `deltaTime` 直接喂给 `QueueDriver.tick`，隐含假设是「一帧 = 一次 update」。
 * 但 SDK 文档说系统查询沿 **Scene → WindowContainer/Stage → SpatialView → Entity** 增量搜索，
 * 「搜索范围通常限于当前空间容器」——暗示 update 是**按容器分发**的。
 *
 * 当前应用只有一个 `DefaultWindowContainer`，所以这个假设成立。但 Task 7/9 一旦开出
 * 第二个容器（子窗口、Augment 窗口等），`driver.tick(delta)` 每帧就会被调 N 次，
 * **过渡动画与 5 分钟自动推进直接快 N 倍**，而且没有任何东西拦得住——
 * 单测覆盖不到（它不经过 ECS），编译期看不出来，观感上只是「怎么翻得这么快」。
 *
 * 真要开多容器，就得把「每帧只跑一次」显式化：加一个帧序号/时间戳去重，
 * 或者把 driver 的推进搬到单一容器的 System 里、其余容器只读快照。
 *
 * 注册/注销由宿主 Composable 用 `registerSystem<HistoryQueueSystem>()` /
 * `unregisterSystem<HistoryQueueSystem>()` 在 `DisposableEffect` 里完成（Task 7/8）。
 */
class HistoryQueueSystem : System() {

    private var lastPublished: QueueSnapshot? = null

    /**
     * 距上次跨午夜检查累计了多少秒。
     *
     * **初值刻意设为满值**，这样容器建立后的第一帧就会立刻检查一次。节流的目的只是防止
     * 每帧调 `LocalDate.now()`，冷启动那一次不需要被节流——否则卡片会以正确姿态摆好、
     * 但**内容全空约 1 秒**才开始装载。
     */
    private var sinceDayCheck = DAY_CHECK_INTERVAL_SECONDS

    /** 已经发出重载请求、尚未等到 [QueueHost] 回信号。 */
    private var reloadInFlight = false

    /** 当前这次 in-flight 已经等了多久，用于 [RELOAD_TIMEOUT_SECONDS] 兜底。 */
    private var reloadWaitSeconds = 0f

    /** [logMissingTransform] 的一次性开关，避免每帧刷屏。 */
    private var missingTransformLogged = false

    /**
     * 上一次记录过的查询命中数，用于 [logQueryCounts] 的变更检测。`-1` 表示还没记过。
     *
     * 存在的理由：`queryEntity` 能否命中 `SpatialView` 经 `attachments` 挂上的实体，
     * 是整条链路里唯一**失败时没有任何报错**的环节——查不到就是每帧遍历一个空列表，
     * 所有面板停在原点相互重叠，与「System 没注册」「组件没挂上」症状完全相同。
     * 这两个计数把三者区分开：0/0 是查不到，1/6 是正常。
     */
    private var loggedCardCount = -1
    private var loggedDateCount = -1

    // EntityQueryCondition 只持有 Function1 + 两个 Set + 一个 boolean，全是 final 字段，
    // 与任何 Scene 实例无关（已用 javap 核实），所以可以安全地提成字段而不必每帧新建。
    private val cardCondition = EntityQueryCondition.hasComponent(HistoryCardComponent::class.java)
    private val exitCondition =
        EntityQueryCondition.hasComponent(ExitCardComponent::class.java)

    private val dateCondition = EntityQueryCondition.hasComponent(DatePanelComponent::class.java)

    override fun update(context: SceneUpdateContext) {
        super.update(context)

        val driver = QueueHost.driver
        if (driver == null) {
            // 容器还没建立或已经销毁。把本实例的状态清干净，重新挂上时才不会带着
            // 上一轮的 in-flight 标志和 lastPublished 引用。
            resetLocalState()
            return
        }

        // 与 QueueDriver.tick 的卫兵同构：非有限或负的 delta 一律当 0 处理。
        // 不这么做的话，单帧 NaN 会让 sinceDayCheck / reloadWaitSeconds 永久变成 NaN
        // （`NaN >= x` 恒 false），跨午夜检查和超时兜底双双静默死掉——与 Task 5 在
        // idleSeconds 上踩过的是同一个坑。
        val delta = context.deltaTime.let { if (it.isFinite() && it >= 0f) it else 0f }

        // 1. 先处理宿主回来的装载结果。reset 会打断进行中的过渡并让姿态瞬间归位
        //    （Task 5 交接第 8 条），所以放在 tick 之前，本帧算出来的就是新数据的姿态。
        applyReloadResult(driver)

        // 2. 消费点击。过渡进行中 requestAdvance 会被 driver 静默忽略（Task 5 交接第 4 条），
        //    这里不补排队——「过渡中的点击丢掉」是当前刻意的手感，要改属于 Task 9 的决策。
        val step = QueueHost.consumePendingStep()
        if (step > 0) {
            driver.requestAdvance(step)
        }

        // 3. 推进计时。顺序按 Task 5 交接第 2 条：先 tick，再 poseOf，再推 snapshot。
        //    这里给的是**未截断**的 delta：5 分钟自动推进计的是真实流逝时间。
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

    /** 落地宿主协程回来的装载结果。成功与失败都会解除 in-flight 状态。 */
    private fun applyReloadResult(driver: QueueDriver) {
        when (val result = QueueHost.consumePendingReload()) {
            is QueueHost.ReloadResult.Loaded -> {
                driver.reset(result.entries, result.month, result.day)
                clearInFlight()
                // 刚装完，下一次跨午夜检查可以等满一个节流窗口再说。
                sinceDayCheck = 0f
            }
            QueueHost.ReloadResult.Failed -> {
                Log.w(LOG_TAG, "装载失败，将在约 ${DAY_CHECK_INTERVAL_SECONDS}s 后重试")
                clearInFlight()
                // 给一个节流窗口的退避，避免持续失败时每帧重试。
                sinceDayCheck = 0f
            }
            null -> Unit
        }
    }

    /**
     * 跨午夜检查，带三层保护。缺任何一层都会出问题：
     *
     * - **秒级节流**：`dayChanged()` 每次调用都走一遍 `LocalDate.now()`，含时区规则查询与
     *   对象分配（Task 5 交接第 7 条）。跨午夜的检测精度用不着每帧，1 秒足够。
     * - **in-flight 去重**：`dayChanged()` 是**电平信号不是边沿信号**（Task 5 交接第 5 条）
     *   ——从「已发起加载」到「`reset` 落地」的整个窗口里它持续为 true。不去重的话，
     *   异步读盘+解析期间每次检查都会再发起一次加载，而每次 `reset` 都会重新随机洗牌并
     *   清零计时，队列会在启动瞬间反复跳变。注意这不是只在跨午夜才出现的边角情况：
     *   首次 `reset` 之前快照是哨兵 `0/0`，`dayChanged()` 恒为 true，**每次冷启动必现**。
     * - **超时兜底**：见 [RELOAD_TIMEOUT_SECONDS]。它**只兜协程失联**，不判定「装载太慢」
     *   ——慢由「等信号」自然处理，等多久都行。
     *
     * 超时预算用的是**截断过的** delta（见 [MAX_TIMEOUT_BUDGET_DELTA_SECONDS]）：
     * 恢复前台或长 GC 之后的单个大 delta 不该一帧吃光预算并误判失联。
     */
    private fun checkDayChange(driver: QueueDriver, delta: Float) {
        if (reloadInFlight) {
            reloadWaitSeconds += delta.coerceAtMost(MAX_TIMEOUT_BUDGET_DELTA_SECONDS)
            if (reloadWaitSeconds < RELOAD_TIMEOUT_SECONDS) return
            // 等太久且一个信号都没回来，判定为协程失联，放开去重标志允许重试。
            Log.w(LOG_TAG, "装载超过 ${RELOAD_TIMEOUT_SECONDS}s 未回信号，按失联处理并重试")
            clearInFlight()
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
     * ### 为什么每帧都写，即使姿态没变
     *
     * 5 分钟里绝大多数帧的姿态是常量。之所以不加「非过渡期就跳过」的守卫，是因为
     * **每帧写是一条强不变量**：只要实体挂着组件，它的位置就一定是权威值。
     * 加了守卫之后，任何在非过渡期新挂上组件的实体（Task 7/8 完全可能分帧挂）
     * 都会**停在原点直到下一次推进**——最长 5 分钟。省下的那点写入不值这个风险。
     * 若 Task 8/9 实测证明重复写入确有成本，再连同「新实体立即定位」的补偿一起加。
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

        val cards = scene.queryEntity(cardCondition)
        val dates = scene.queryEntity(dateCondition)
        logQueryCounts(cards.size, dates.size)

        cards.forEach { entity ->
            val card = entity.components[HistoryCardComponent::class.java] ?: return@forEach
            val transform = entity.components[TransformComponent::class.java]
            if (transform == null) {
                logMissingTransform("卡片 slot=${card.slot}")
                return@forEach
            }
            applyPose(transform, driver.poseOf(card.slot), bounds)
        }

        // 离场卡：位置走 exitPoseNow()，不透明度走实体级 OpacityControllerComponent。
        // 用实体级而非 Compose alpha 的原因见 ExitCardComponent 的 KDoc。
        scene.queryEntity(exitCondition).forEach { entity ->
            val transform = entity.components[TransformComponent::class.java]
            if (transform == null) {
                logMissingTransform("离场卡")
                return@forEach
            }
            val pose = driver.exitPoseNow()
            applyPose(transform, pose, bounds)
            entity.components[OpacityControllerComponent::class.java]?.opacity = pose.alpha
        }

        // 日期牌姿态恒定，但仍然每帧写：boundsMeters 会随容器缩放变化，而 System 侧
        // 收不到任何变更通知。一个实体的写入开销可以忽略，换来「缩放后自动跟随」。
        dates.forEach { entity ->
            val transform = entity.components[TransformComponent::class.java]
            if (transform == null) {
                logMissingTransform("日期牌")
                return@forEach
            }
            applyPose(transform, SlotLayout.clockPose, bounds)
        }
    }

    /**
     * 命中数变化时记一条。**只在变化时记**，所以稳态下一条都不打，不是每帧刷屏。
     *
     * 期望序列是 `0/0`（实体还没挂上的头几帧）→ `6/1`。若始终停在 `0/0`，
     * 说明 `queryEntity` 查不到 attachment 实体，这时**不要**去调姿态表——
     * 姿态根本没被写进去过。
     */
    private fun logQueryCounts(cardCount: Int, dateCount: Int) {
        if (cardCount == loggedCardCount && dateCount == loggedDateCount) return
        loggedCardCount = cardCount
        loggedDateCount = dateCount
        Log.i(LOG_TAG, "查询命中：卡片=$cardCount，日期牌=$dateCount")
    }

    private fun applyPose(transform: TransformComponent, pose: SlotPose, bounds: Vector3) {
        transform.position =
            Vector3(pose.xFactor * bounds.x, pose.yFactor * bounds.y, pose.zFactor * bounds.z)
        transform.scaleVector = Vector3(pose.scale, pose.scale, pose.scale)
        transform.eulerAngles = EulerAngles(0f, pose.yawDegrees, 0f)
    }

    /**
     * 实体挂了标记组件却没有 `TransformComponent` 时报一次警。
     *
     * 静默跳过本身是对的（不能崩），但它的失败表现是「卡片全堆在原点」，与
     * 「System 没注册」「组件没挂上」**症状完全相同**，三者在设备上根本分不开。
     * `HomeVolume.kt` 现有代码用的正是可空访问
     * （`components[TransformComponent::class.java]?.apply { ... }`），说明 attachment
     * 实体的这个组件**确实可能为 null**，不是理论风险。所以这里必须留下痕迹。
     *
     * 一次性：每个 System 实例只打一条，避免每帧刷屏。
     */
    private fun logMissingTransform(what: String) {
        if (missingTransformLogged) return
        missingTransformLogged = true
        Log.w(LOG_TAG, "$what 的实体没有 TransformComponent，姿态无法应用（会表现为停在原点）")
    }

    private fun clearInFlight() {
        reloadInFlight = false
        reloadWaitSeconds = 0f
    }

    private fun resetLocalState() {
        lastPublished = null
        // 与字段初值一致：重新挂上时第一帧就检查，不白等一个节流窗口。
        sinceDayCheck = DAY_CHECK_INTERVAL_SECONDS
        // 重新挂上时把命中数重新记一次，否则新容器的接线结果不会留下任何痕迹。
        loggedCardCount = -1
        loggedDateCount = -1
        clearInFlight()
    }
}
