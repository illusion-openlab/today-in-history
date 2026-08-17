package tech.illusion.todayinhistory.queue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

private fun entries(vararg years: Int): List<HistoryEntry> =
    years.map { HistoryEntry(it, "第 $it 年", Kind.EVENT) }

class HistoryQueueTest {

    @Test
    fun `槽位从游标开始依次取条目`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        assertEquals(1, q.entryAt(0)!!.year)
        assertEquals(2, q.entryAt(1)!!.year)
        assertEquals(6, q.entryAt(5)!!.year)
    }

    @Test
    fun `从非零起点开始`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 3)

        assertEquals(4, q.entryAt(0)!!.year)
        assertEquals(5, q.entryAt(1)!!.year)
    }

    @Test
    fun `前进一条后焦点变成原来的第二条`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        q.advance()

        assertEquals(2, q.entryAt(0)!!.year)
        assertEquals(7, q.entryAt(5)!!.year)
    }

    @Test
    fun `前进多条`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        q.advance(step = 3)

        assertEquals(4, q.entryAt(0)!!.year)
    }

    @Test
    fun `槽位越过末尾时环绕回开头`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 6)

        assertEquals(7, q.entryAt(0)!!.year)
        assertEquals(8, q.entryAt(1)!!.year)
        assertEquals(1, q.entryAt(2)!!.year) // 环绕
        assertEquals(2, q.entryAt(3)!!.year)
    }

    @Test
    fun `游标环绕一整圈后回到起点`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance(step = 3)

        assertEquals(0, q.cursor)
        assertEquals(1, q.entryAt(0)!!.year)
    }

    @Test
    fun `步长大于总数时取模`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance(step = 7) // 7 mod 3 == 1

        assertEquals(2, q.entryAt(0)!!.year)
    }

    @Test
    fun `条目数少于槽位数时多余槽位为空`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        assertEquals(1, q.entryAt(0)!!.year)
        assertEquals(2, q.entryAt(1)!!.year)
        assertEquals(3, q.entryAt(2)!!.year)
        assertNull(q.entryAt(3))
        assertNull(q.entryAt(5))
    }

    @Test
    fun `空队列所有槽位为空且前进是空操作`() {
        val q = HistoryQueue(emptyList(), startIndex = 0)

        assertEquals(0, q.size)
        assertNull(q.entryAt(0))

        q.advance() // 不应抛异常

        assertEquals(0, q.cursor)
    }

    @Test
    fun `槽位号越界返回空`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        assertNull(q.entryAt(-1))
        assertNull(q.entryAt(VISIBLE_SLOTS))
    }

    // ---- 以下为对 brief 的补强与新增（详见 task-3-report.md）----

    /**
     * brief 的所有用例都只喂正数，`%` 和 `Int.mod` 在正数上完全等价——
     * 也就是说光靠 brief 那 12 条，一个用 `%` 写的错误实现能全绿。
     * 下面四条负数/超界用例才是真正把 `mod` 语义钉死的断言。
     */
    @Test
    fun `负步长回退并环绕到末尾`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        q.advance(step = -1) // (0 - 1).mod(8) == 7，用 % 会得到 -1

        assertEquals(7, q.cursor)
        assertEquals(8, q.entryAt(0)!!.year)
        assertEquals(1, q.entryAt(1)!!.year)
    }

    @Test
    fun `负步长绝对值大于总数时游标仍非负`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance(step = -7) // (0 - 7).mod(3) == 2

        assertEquals(2, q.cursor)
        assertEquals(3, q.entryAt(0)!!.year)
    }

    @Test
    fun `负起点被归一化成非负游标`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = -1)

        assertEquals(7, q.cursor)
        assertEquals(8, q.entryAt(0)!!.year)

        val far = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = -9)

        assertEquals(7, far.cursor)
    }

    @Test
    fun `起点大于总数时被归一化`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 11)

        assertEquals(3, q.cursor)
        assertEquals(4, q.entryAt(0)!!.year)
    }

    @Test
    fun `空队列上任何步长与槽位都安全`() {
        val q = HistoryQueue(emptyList(), startIndex = 5)

        assertEquals(0, q.cursor) // 空队列不该被起点带偏
        for (slot in -1..VISIBLE_SLOTS) {
            assertNull("slot $slot 应为空", q.entryAt(slot))
        }

        q.advance(step = -3)
        q.advance(step = 999)

        assertEquals(0, q.cursor)
    }

    @Test
    fun `单条目队列的焦点恒定且待读槽位为空`() {
        val only = entries(1970)
        val q = HistoryQueue(only, startIndex = 0)

        assertEquals(1970, q.entryAt(0)!!.year)
        assertNull(q.entryAt(1))

        q.advance()

        assertEquals(0, q.cursor)
        assertEquals(1970, q.entryAt(0)!!.year)
    }

    @Test
    fun `entryAt 是纯读取不推进游标`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 2)

        repeat(3) { for (slot in 0 until VISIBLE_SLOTS) q.entryAt(slot) }

        assertEquals(2, q.cursor)
        assertEquals(3, q.entryAt(0)!!.year)
    }

    @Test
    fun `步长为零不动`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 4)

        q.advance(step = 0)

        assertEquals(4, q.cursor)
        assertEquals(5, q.entryAt(0)!!.year)
    }

    @Test
    fun `最后一个可见槽位仍有值且不重排原始条目`() {
        // 公元前（负年份）与不同 Kind 混排，确认队列只做环形取址、不做任何排序或复制。
        val list = listOf(
            HistoryEntry(-221, "秦统一", Kind.EVENT),
            HistoryEntry(1893, "某人出生", Kind.BIRTH),
            HistoryEntry(1976, "某人逝世", Kind.DEATH),
            HistoryEntry(-500, "更早的事", Kind.EVENT),
            HistoryEntry(2001, "新世纪", Kind.EVENT),
            HistoryEntry(1900, "世纪之交", Kind.EVENT),
        )
        val q = HistoryQueue(list, startIndex = 0)

        assertEquals(list.size, q.size)
        assertNotNull(q.entryAt(VISIBLE_SLOTS - 1))
        for (slot in 0 until VISIBLE_SLOTS) {
            assertSame("slot $slot 应原样返回第 $slot 条", list[slot], q.entryAt(slot))
        }
        assertEquals(-221, q.entryAt(0)!!.year)
        assertEquals(Kind.DEATH, q.entryAt(2)!!.kind)
    }

    @Test
    fun `条目不足且游标非零时槽位环绕但不重影`() {
        // size < VISIBLE_SLOTS 且 cursor != 0——「多余槽位为空」这条规则真正的用武之地。
        // 先前 size=3 的用例 cursor 恒为 0，size=8 的用例又填满了全部槽位，两者都没覆盖
        // 到这个组合。slot 在 0 until size 上取 (cursor + slot).mod(size) 是双射，所以
        // slot >= size 精确等价于「该槽位会与某个更小槽位撞号」，null 是去重而非兜底。
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance()

        assertEquals(1, q.cursor)
        assertEquals(2, q.entryAt(0)!!.year)
        assertEquals(3, q.entryAt(1)!!.year)
        assertEquals(1, q.entryAt(2)!!.year) // 环绕回开头
        assertNull(q.entryAt(3)) // 若在此处再环绕，第 2 年就会同时出现在 slot 0 和 slot 3
        assertNull(q.entryAt(VISIBLE_SLOTS - 1))
    }

    @Test
    fun `可见槽位数是六`() {
        // 1 张焦点卡 + 5 条待读。下游布局直接引用这个常量，改动它等于改产品形态。
        assertEquals(6, VISIBLE_SLOTS)
    }

    @Test
    fun `randomStart 产生的起点落在合法区间内`() {
        val list = entries(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        repeat(50) { seed ->
            val q = HistoryQueue.randomStart(list, Random(seed))
            // 原 brief 用的是 Kotlin `assert`，它受 JVM -ea 开关控制，关掉断言时整条测试恒真。
            assertTrue("游标 ${q.cursor} 越界", q.cursor in list.indices)
            assertEquals(list.size, q.size)
            // 起点必须真的成为焦点，而不是只把 cursor 存下来。
            assertSame(list[q.cursor], q.entryAt(0))
        }
    }

    @Test
    fun `randomStart 覆盖整个下标区间且起点完全由传入的 Random 决定`() {
        // 只断言「不越界」的话，一个恒返回 0 的实现也能通过。这里要求固定的 200 个种子
        // 恰好覆盖全部下标：既排除常量起点，也排除 nextInt(size - 1)、nextInt(1, size)
        // 这类两端各差一格的写法。
        val list = entries(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        // 同一批种子跑两遍，要求整条序列逐位相同。
        //
        // 早先这里只比「同一种子连抽两次是否相等」，那是弱断言：一个忽略入参、
        // 改用全局 Random.Default 的实现有约 1/10 的概率两次抽到同值而放行，
        // 于是同一份错误代码时红时绿。逐位比整条 200 元素序列后，全局随机源要蒙混
        // 过关需要连续 200 次巧合（约 1e-200），实际上是稳定红。
        fun cursorsOfFixedSeeds(): List<Int> =
            (0 until 200).map { seed -> HistoryQueue.randomStart(list, Random(seed)).cursor }

        val firstPass = cursorsOfFixedSeeds()
        val secondPass = cursorsOfFixedSeeds()

        assertEquals("同一批种子必须复现出同一条起点序列", firstPass, secondPass)
        assertEquals(list.indices.toSet(), firstPass.toSet())
    }

    @Test
    fun `randomStart 对单条目列表返回下标零`() {
        // nextInt 的上界是开区间：size == 1 时只能取 0，且不能退化成 nextInt(0) 抛异常。
        val q = HistoryQueue.randomStart(entries(1970), Random(7))

        assertEquals(0, q.cursor)
        assertEquals(1970, q.entryAt(0)!!.year)
    }

    @Test
    fun `randomStart 对空列表返回空队列`() {
        val q = HistoryQueue.randomStart(emptyList(), Random(0))

        assertEquals(0, q.size)
        assertEquals(0, q.cursor)
    }
}
