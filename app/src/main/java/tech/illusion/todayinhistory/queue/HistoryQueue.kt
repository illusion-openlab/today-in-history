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
 *
 * @param entries 队列内容，按展示顺序排列。**队列不做防御性拷贝，调用方交出后就不得再
 *   修改这个 list。** `HistoryRepository.loadDay` 返回的是可变 `ArrayList`：若下游持有
 *   同一个引用并 `clear()`，[size] 立刻变 0，[advance] 静默成为 no-op、[entryAt] 全部
 *   返回 null——不抛任何异常，UI 只是静默变空，属于极难定位的一类故障。
 * @param startIndex 切入位置。语义是「一个位置」而非「一个合法下标」：**任意整数都会被
 *   环绕**（对 [size] 取 `mod`），越界既不报错也不截断。负数同样环绕（`-1` 即最后一条），
 *   这一行为已被单测钉成契约，因此这里刻意不加 `require`。
 *
 *   需要留意的风险场景是「持久化 cursor 跨天恢复」：昨天存下 200，今天这一天只有 67 条，
 *   200 会被静默环绕成 66，也就是从队列**倒数第一条**开始读，既不报错也不回退到 0。
 *   若将来要做跨天恢复，请在构造之前自行校验下标是否仍然有效。
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
