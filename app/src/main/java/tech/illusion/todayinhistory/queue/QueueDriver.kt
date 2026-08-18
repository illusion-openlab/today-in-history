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

    /** 正在向左滑出的那条；非过渡期为 null。见 [exitingEntry] 与 [exitPose]。 */
    private var exitingEntry: HistoryEntry? = null

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
        exitingEntry = null
        publish(month, day)
    }

    /**
     * 每帧调用。[deltaSeconds] 来自 SceneUpdateContext.deltaTime。
     *
     * 非有限或负的 [deltaSeconds] 一律**整帧丢弃**，与 `SlotLayout.normalizeProgress`
     * 的防线同构。四种非法值的后果各不相同，**别当成同一件事**（下列行为均已实测）：
     *
     * | 值 | 落到 `idleSeconds` 之后 | 能否自愈 |
     * |---|---|---|
     * | `NaN` | `NaN >= 300f` 恒为 false，自动推进静默死掉 | **不能** |
     * | `-Inf` | `-Inf + 有限值` 恒为 `-Inf`，同上 | **不能** |
     * | `+Inf` | `Inf >= 300f` 为真 → 立刻**误推进一条**，随后 `requestAdvance` 把计时清零 | 能，但已经错跳了一条 |
     * | 有限负数 | 计时倒退，自动推进被推迟 `|delta|` 秒 | 能 |
     *
     * 前两者是真正致命的：只有 [reset] 或用户手动点击才能救回来，对一个「打开就放着
     * 自己翻」的摆件，等于熬到下一次跨午夜重载（最长约 24 小时）都不动。
     * [secondsUntilAutoAdvance] 也挡不住——`coerceAtLeast` 内部是
     * `if (this < min) min else this`，而 `NaN < 0f` 为 false（NaN 原样透出）、
     * `300f - (-Inf)` 得 `+Inf` 且 `Inf < 0f` 为 false（`+Inf` 原样透出）。
     *
     * 注意 `Inf - Inf` 得 NaN 这件事**在本路径上不会发生**：[secondsUntilAutoAdvance]
     * 算的是 `300f - Inf = -Inf`（再被钳成 `0f`）；要凑出 NaN 得连着两帧异号的无穷大。
     *
     * **`0f` 必须放过，不要把条件收紧成 `<= 0f`。** 丢弃 `0f` 既无实际效果（卫兵是
     * `return`，与 `+= 0f` 的唯一差别是不执行过渡终止判定，状态完全相同），又会拆掉
     * `SlotLayout.normalizeProgress` KDoc 里「NaN 归 0f 最多波及一帧」那个上界——
     * 那个上界正是靠终止判定 `>=` 在 `delta == 0` 的帧也成立撑住的。
     * （这条约束没有测试能钉死：`TRANSITION_SECONDS > 0` 时丢弃 `0f` 是不可观测的，
     * 差别只在 `TRANSITION_SECONDS == 0` 时才显形。所以靠这段注释守着。）
     *
     * `transitionSeconds` 的失效方式与 `idleSeconds` **不同**：它变 NaN 会让
     * [isTransitioning]（`NaN >= 0f` 为 false）直接掉出过渡态，动画被吃掉但可自愈。
     * 两条路径别混为一谈，也别拿一条的结论去推另一条。
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

        // 推进前先记下即将离场的那条：advance() 之后它就不在快照里了，
        // 而离场动画需要它继续显示 TRANSITION_SECONDS 秒。
        exitingEntry = queue.entryAt(0)
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

    /**
     * 正在离场的条目；非过渡期为 null。
     *
     * 它已经不在 [snapshot] 里了——`advance()` 把游标推走的那一刻它就出队了。
     * 但视觉上它还要在屏幕上向左滑 [TRANSITION_SECONDS] 秒，所以由这里单独持有。
     */
    fun exitingEntry(): HistoryEntry? = if (isTransitioning) exitingEntry else null

    /**
     * 离场卡此刻的姿态：从 slot 0 滑向 [SlotLayout.exitPose]。
     *
     * 非过渡期返回 [SlotLayout.exitPose] 本身（alpha 为 0），这样离场卡在静止时
     * 完全不可见，不需要额外的显隐开关。
     */
    fun exitPoseNow(): SlotPose {
        if (!isTransitioning) return SlotLayout.exitPose
        val t = SlotLayout.easeOutCubic(transitionSeconds / TRANSITION_SECONDS)
        return SlotLayout.lerp(SlotLayout.poseFor(0), SlotLayout.exitPose, t)
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
     * 不需要重载。这也避开了另一个坑——若改成拿
     * `LocalDate.of(clock().year, snapshot.month, snapshot.day)` 去凑一个基准日期，
     * 在 `snapshot` 是 `2/29` 而当前年份是平年时会**直接抛 `DateTimeException`**
     * （实测：`LocalDate.of(2027, 2, 29)` → `Invalid date 'February 29' as '2027' is not a
     * leap year`）。按月日比较不会有这个问题。
     *
     * 首次 [reset] 之前快照是 [QueueSnapshot.EMPTY] 的哨兵日期 `0/0`，本方法返回 true，
     * 语义上正是「还没装数据，需要加载」。
     *
     * ### 两条使用约束
     *
     * 1. **这是电平信号，不是边沿信号。** 从「已发起加载」到「[reset] 回调落地」的整个窗口里
     *    本方法**持续为 true**。调用方**必须自己维护 in-flight 去重标志**，否则异步读盘+解析
     *    期间每次检查都会再发起一次加载，而每次 [reset] 都会重新随机洗牌并清零计时——
     *    队列会在启动瞬间反复跳变。
     * 2. **不要用 [reset] 装载非今日的数据。** 本方法把「展示日期」与「重载基准」绑在了
     *    `snapshot` 的月日这同一个字段上。若拿 `reset(entries, 非今日月日)` 去做「浏览昨天 /
     *    某周年」，本方法会**恒为 true**，调用方下一次检查就会把用户拽回今天并重新洗牌，
     *    功能静默自毁。将来要支持浏览任意日期，须另加 `pinnedDate` 之类的显式基准，
     *    把两个职责拆开。
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
                exiting = exitingEntry,
            )
    }
}
