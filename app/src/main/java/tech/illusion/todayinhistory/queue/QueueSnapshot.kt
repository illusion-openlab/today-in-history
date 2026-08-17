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
data class QueueSnapshot(val slots: List<SlotContent>, val month: Int, val day: Int) {
    companion object {
        val EMPTY =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, null) },
                month = 1,
                day = 1,
            )
    }
}
