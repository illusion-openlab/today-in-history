package tech.illusion.todayinhistory.queue

import java.time.LocalDate
import kotlin.random.Random
import tech.illusion.todayinhistory.data.HistoryEntry

/** 无操作时的自动推进间隔。 */
const val AUTO_ADVANCE_SECONDS = 300f

/** 一次推进的过渡时长。 */
const val TRANSITION_SECONDS = 0.6f

/**
 * 队列的全部时间相关逻辑，纯 Kotlin，无 SDK 依赖，因此可以完整单测。
 *
 * ECS 侧（HistoryQueueSystem）只做三件事：每帧调 [tick]、把 [poseOf] 的结果写进
 * TransformComponent、把 [snapshot] 推给 Compose。
 *
 * @param clock 注入当前日期，测试里可控；生产传 `{ LocalDate.now() }`
 */
class QueueDriver(private val clock: () -> LocalDate, private val random: Random) {

    private var queue = HistoryQueue(emptyList(), 0)

    private var idleSeconds = 0f
    private var transitionSeconds = -1f
    private var transitionStep = 0

    var snapshot: QueueSnapshot = QueueSnapshot.EMPTY
        private set

    val isTransitioning: Boolean
        get() = transitionSeconds >= 0f

    /** 装载某天的数据，随机选起点，重置计时。 */
    fun reset(entries: List<HistoryEntry>, month: Int, day: Int) {
        queue = HistoryQueue.randomStart(entries, random)
        idleSeconds = 0f
        transitionSeconds = -1f
        transitionStep = 0
        publish(month, day)
    }

    /**
     * 每帧调用。[deltaSeconds] 来自 SceneUpdateContext.deltaTime。
     *
     * 非有限或负的 [deltaSeconds] 一律**整帧丢弃**，与 `SlotLayout.normalizeProgress`
     * 的防线同构。这不是洁癖：`idleSeconds` 一旦被 `+= NaN` 污染就**没有任何自愈路径**
     * ——`NaN >= AUTO_ADVANCE_SECONDS` 恒为 false，自动推进从此静默死掉，只有 [reset]
     * 或用户手动点击才能救回来；对一个「打开就放着自己翻」的摆件，那等于熬到下一次
     * 跨午夜重载（最长约 24 小时）都不动。`secondsUntilAutoAdvance` 也挡不住，
     * `coerceAtLeast` 内部是 `if (this < min) min else this`，`NaN < 0f` 为 false，
     * NaN 会原样透出。±Inf 同理（`Inf - Inf` 得 NaN）。
     *
     * `transitionSeconds` 的失效方式与之不同：它变 NaN 会让 [isTransitioning]
     * （`NaN >= 0f` 为 false）直接掉出过渡态，动画被吃掉但可自愈。两条路径别混为一谈。
     */
    fun tick(deltaSeconds: Float) {
        if (!deltaSeconds.isFinite() || deltaSeconds < 0f) return

        if (isTransitioning) {
            transitionSeconds += deltaSeconds
            if (transitionSeconds >= TRANSITION_SECONDS) {
                transitionSeconds = -1f
                transitionStep = 0
            }
            return
        }

        if (queue.size == 0) return

        idleSeconds += deltaSeconds
        if (idleSeconds >= AUTO_ADVANCE_SECONDS) {
            requestAdvance(step = 1)
        }
    }

    /**
     * 请求前进 [step] 条。点焦点卡传 1，点第 i 条待读传 i。
     * 过渡进行中的请求会被忽略，避免动画打架。
     */
    fun requestAdvance(step: Int) {
        if (queue.size == 0 || isTransitioning || step <= 0) return

        queue.advance(step)
        idleSeconds = 0f
        transitionSeconds = 0f
        transitionStep = step
        publish(snapshot.month, snapshot.day)
    }

    /**
     * 第 [slot] 个槽位此刻的姿态。
     *
     * **[slot] 是推进之后的槽位号，与 [snapshot] 的 `slots` 同序。** 推进刚发生时
     * `advance()` 与 `publish()` 已经跑完，`snapshot.slots[i]` 是新内容；那张卡上一帧
     * 还在 `i + step`，所以过渡期间从 `poseFor(slot + step)` 插值到 `poseFor(slot)`。
     * 不要传「上一帧的槽位号」。
     *
     * ### 端点退化（Task 6 必须知道）
     *
     * `SlotLayout.poseFor` 把越界槽位钳到 `0..VISIBLE_SLOTS-1`，于是队列两端的动画是残缺的：
     *
     * - **尾部**：step=1 时 `poseOf(5)` 的起点 `poseFor(6)` 被钳成 `poseFor(5)`，与目标相同
     *   ——新进队的卡**原地不动**没有滑入，且在 t=0 与仍承载旧 slot 5 内容的实体**完全重叠**。
     *   step=3 时 slot 3/4/5 三张卡全部从 `poseFor(5)` 起步，退化范围随 step 变大。
     * - **头部**：被挤出去的旧焦点卡根本不在新快照里，是**瞬间消失**，没有淡出。
     *
     * 所以实际观感是「中间几张滑，两端硬切」，而不是「整列一起滑」。这是 6 槽固定窗口
     * 模型的必然结果，不是本类的缺陷——真要补，得由 Task 6 给入场/出场卡另做 alpha
     * 淡入淡出，或多渲染一张队尾缓冲卡。
     */
    fun poseOf(slot: Int): SlotPose {
        val target = SlotLayout.poseFor(slot)
        if (!isTransitioning) return target

        val origin = SlotLayout.poseFor(slot + transitionStep)
        val t = SlotLayout.easeOutCubic(transitionSeconds / TRANSITION_SECONDS)
        return SlotLayout.lerp(origin, target, t)
    }

    /** 距下一次自动推进还剩多少秒。 */
    fun secondsUntilAutoAdvance(): Float = (AUTO_ADVANCE_SECONDS - idleSeconds).coerceAtLeast(0f)

    /**
     * 系统日期是否已经跨过**快照装的那一天**——摆件整夜开着时会发生。
     *
     * 基准取自 [snapshot] 的月日，也就是「我实际装的是哪一天」，而**不是** `reset` 时刻的
     * `clock()`。后者有跨午夜竞态：23:59:59.9 发起加载 8/17 的数据、00:00:00.1 才回调
     * `reset(entries, 8, 17)`，基准会被记成 8/18 而内容是 8/17，本方法返回 false，
     * 接下来约 24 小时都不再重载，日期牌与内容双双停在昨天。
     *
     * 只比月日、不比年：本应用按 `(月, 日)` 取数，2027-08-17 与 2026-08-17 内容相同，
     * 不需要重载。（也因此避开了年份边界——用 `LocalDate.of(clock().year, month, day)`
     * 去凑一个基准日期，在跨年那一刻会算出错误的年份。）
     *
     * 首次 [reset] 之前快照是 [QueueSnapshot.EMPTY] 的哨兵日期 `0/0`，本方法返回 true，
     * 语义上正是「还没装数据，需要加载」。
     *
     * 每次调用都会走一遍 [clock]。生产实现是 `LocalDate.now()`，含时区规则查询与对象分配，
     * **不要每帧调**——按秒级节流即可，跨午夜的检测精度用不着每帧。
     */
    fun dayChanged(): Boolean {
        val today = clock()
        return today.monthValue != snapshot.month || today.dayOfMonth != snapshot.day
    }

    private fun publish(month: Int, day: Int) {
        snapshot =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, queue.entryAt(it)) },
                month = month,
                day = day,
            )
    }
}
