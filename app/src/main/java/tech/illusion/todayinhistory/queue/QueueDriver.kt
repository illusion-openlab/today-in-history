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
    private var loadedDate: LocalDate = clock()

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
        loadedDate = clock()
        idleSeconds = 0f
        transitionSeconds = -1f
        transitionStep = 0
        publish(month, day)
    }

    /** 每帧调用。[deltaSeconds] 来自 SceneUpdateContext.deltaTime。 */
    fun tick(deltaSeconds: Float) {
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
     * 推进刚发生时，队列已经切换到新内容，但视觉上卡片应该从「旧槽位」滑到「新槽位」，
     * 所以过渡期间从 slot + step 插值到 slot。
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

    /** 系统日期是否已经跨过装载时的那一天——摆件整夜开着时会发生。 */
    fun dayChanged(): Boolean = clock() != loadedDate

    private fun publish(month: Int, day: Int) {
        snapshot =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, queue.entryAt(it)) },
                month = month,
                day = day,
            )
    }
}
