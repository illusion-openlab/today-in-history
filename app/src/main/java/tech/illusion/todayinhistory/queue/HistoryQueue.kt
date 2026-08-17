package tech.illusion.todayinhistory.queue

import kotlin.random.Random
import tech.illusion.todayinhistory.data.HistoryEntry

/** 同时可见的槽位数：slot 0 是焦点卡，slot 1..5 是待读条。 */
const val VISIBLE_SLOTS = 6

/**
 * 循环阅读队列。
 *
 * [entries] 已按年份升序排好（构建期完成），队列不再重排——从随机起点切入并绕一圈，
 * 既每次打开都不同，又保住了深度轴的时间语义。
 */
class HistoryQueue(private val entries: List<HistoryEntry>, startIndex: Int) {

    val size: Int
        get() = entries.size

    var cursor: Int = if (entries.isEmpty()) 0 else startIndex.mod(entries.size)
        private set

    /** 取第 [slot] 个槽位当前该显示的条目；槽位越界或条目不足时返回 null。 */
    fun entryAt(slot: Int): HistoryEntry? {
        if (slot !in 0 until VISIBLE_SLOTS) return null
        if (slot >= entries.size) return null
        return entries[(cursor + slot).mod(entries.size)]
    }

    /** 游标前进 [step] 条并环绕。空队列时为空操作。 */
    fun advance(step: Int = 1) {
        if (entries.isEmpty()) return
        cursor = (cursor + step).mod(entries.size)
    }

    companion object {
        /** 冷启动时调用一次：从随机位置切入。 */
        fun randomStart(entries: List<HistoryEntry>, random: Random): HistoryQueue =
            HistoryQueue(
                entries = entries,
                startIndex = if (entries.isEmpty()) 0 else random.nextInt(entries.size),
            )
    }
}
