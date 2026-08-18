package tech.illusion.todayinhistory.queue

import tech.illusion.todayinhistory.data.HistoryEntry

/** 某个槽位当前该显示什么；[entry] 为 null 表示该槽位空着（条目不足）。 */
data class SlotContent(val slot: Int, val entry: HistoryEntry?)

/**
 * System 发布给 Compose 的只读快照。
 *
 * 这是 3D 侧到 2D 侧的**单向**通道：面板内容随快照重组，但 3D 变换永远由
 * System 直接写 TransformComponent，不经过 Compose。
 */
data class QueueSnapshot(
    val slots: List<SlotContent>,
    val month: Int,
    val day: Int,
    /**
     * 正在向左滑出的那条；非过渡期为 null。
     *
     * 它已经不在 [slots] 里了——推进的那一刻就出队了——但视觉上还要再存在
     * 一个过渡时长。放进快照而不是让 UI 去问 driver，是为了让「UI 该显示什么」
     * 始终只有一个来源。
     */
    val exiting: HistoryEntry? = null,
) {
    companion object {
        /**
         * 首次 `QueueDriver.reset` 之前的占位快照。
         *
         * 月日是**明显非法的哨兵 `0`**，不是 `1/1`：这个快照会被 Compose 真的读到，
         * 若填 `1/1`，日期牌会闪一帧「1 月 1 日」这种看起来完全合理的假日期，
         * 没人会发现是 bug。`0` 则要么被下游显式挡掉，要么渲染成明显异常的东西。
         *
         * 配套约定：**首次 reset 之前不要渲染日期牌。** `QueueDriver.dayChanged()`
         * 在这个哨兵下返回 true，正好可以拿来判断「还没装数据」。
         */
        val EMPTY =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, null) },
                month = 0,
                day = 0,
            )
    }
}
