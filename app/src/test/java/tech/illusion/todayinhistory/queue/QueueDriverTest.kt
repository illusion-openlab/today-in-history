package tech.illusion.todayinhistory.queue

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

private const val EPS = 1e-4f

private fun entries(vararg years: Int): List<HistoryEntry> =
    years.map { HistoryEntry(it, "第 $it 年", Kind.EVENT) }

private fun driver(
    today: LocalDate = LocalDate.of(2026, 8, 17),
    seed: Int = 0,
): Pair<QueueDriver, MutableList<LocalDate>> {
    val now = mutableListOf(today)
    val d = QueueDriver(clock = { now.first() }, random = Random(seed))
    return d to now
}

class QueueDriverTest {

    @Test
    fun `reset 后快照填满所有槽位`() {
        val (d, _) = driver()

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), month = 8, day = 17)

        assertEquals(VISIBLE_SLOTS, d.snapshot.slots.size)
        assertEquals(8, d.snapshot.month)
        assertEquals(17, d.snapshot.day)
        assertTrue(d.snapshot.slots.all { it.entry != null })
    }

    @Test
    fun `不到 5 分钟不自动推进`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val before = d.snapshot.slots[0].entry

        d.tick(AUTO_ADVANCE_SECONDS - 1f)

        assertEquals(before, d.snapshot.slots[0].entry)
    }

    @Test
    fun `满 5 分钟自动推进一条`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val first = d.snapshot.slots[0].entry!!.year
        val second = d.snapshot.slots[1].entry!!.year

        d.tick(AUTO_ADVANCE_SECONDS)

        assertNotEquals(first, d.snapshot.slots[0].entry!!.year)
        assertEquals(second, d.snapshot.slots[0].entry!!.year)
    }

    @Test
    fun `手动推进后计时器重置`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(AUTO_ADVANCE_SECONDS - 10f)
        d.requestAdvance(step = 1)
        d.tick(0f)

        assertEquals(AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)
    }

    @Test
    fun `自动推进后计时器也重置`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(AUTO_ADVANCE_SECONDS)

        assertEquals(AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)
    }

    @Test
    fun `倒计时随 tick 累计递减`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        assertEquals(AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)

        d.tick(100f)
        assertEquals(AUTO_ADVANCE_SECONDS - 100f, d.secondsUntilAutoAdvance(), 0.01f)

        d.tick(50f)
        assertEquals(AUTO_ADVANCE_SECONDS - 150f, d.secondsUntilAutoAdvance(), 0.01f)
    }

    /** 过渡期间的那几帧只推进动画，不该同时消耗自动推进的空闲计时。 */
    @Test
    fun `过渡期间不累计空闲计时`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(100f)
        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS / 2f)
        d.tick(TRANSITION_SECONDS / 2f)

        assertEquals(AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)
    }

    @Test
    fun `手动跳转多条`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val target = d.snapshot.slots[3].entry!!.year

        d.requestAdvance(step = 3)
        d.tick(0f)

        assertEquals(target, d.snapshot.slots[0].entry!!.year)
    }

    @Test
    fun `推进开始时进入过渡状态 结束后退出`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(0f)
        assertTrue(d.isTransitioning)

        d.tick(TRANSITION_SECONDS)
        assertFalse(d.isTransitioning)
    }

    /**
     * 注意 [QueueDriver.poseOf] 的索引约定：参数是**推进之后**的槽位号，与
     * `snapshot.slots` 同序。推进时 `advance()` 已经先跑完，`poseOf(1)` 问的是
     * 「现在排在 slot 1 的那张卡」，它上一帧还在 slot 1 + step，所以插值区间是
     * `poseFor(1 + step)` -> `poseFor(1)`，而**不是** `poseFor(1)` -> `poseFor(0)`。
     * 后者是「旧槽位号」的读法，与 [QueueDriver.snapshot] 对不上，也与
     * `过渡结束后姿态精确落在目标槽位` 自相矛盾。
     */
    @Test
    fun `过渡中槽位姿态介于起点槽位与目标槽位之间`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS / 2f)

        // 现在排在 slot 1 的卡正从 slot 2 往 slot 1 移动，z 应落在两者之间
        val pose = d.poseOf(1)
        val from = SlotLayout.poseFor(2)
        val to = SlotLayout.poseFor(1)

        assertTrue("z=${pose.zFactor} 应大于 ${from.zFactor}", pose.zFactor > from.zFactor)
        assertTrue("z=${pose.zFactor} 应小于 ${to.zFactor}", pose.zFactor < to.zFactor)
    }

    /**
     * 单纯的「介于两端之间」挡不住 origin/target 互换：互换后 z = -0.04f，
     * 照样落在 (-0.06f, 0.10f) 里。所以这里把中途值钉死到具体数字。
     *
     * 走过一半物理时长时进度是 `easeOutCubic(0.5f) == 0.875f`——远过中点，
     * 因为缓动是缓出。
     */
    @Test
    fun `过渡中途的姿态取值精确`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS / 2f)

        val expected =
            SlotLayout.lerp(SlotLayout.poseFor(2), SlotLayout.poseFor(1), SlotLayout.easeOutCubic(0.5f))
        assertEquals(expected, d.poseOf(1))

        // 非同义反复的独立核对：-0.06 * 0.125 + 0.10 * 0.875 == 0.08
        assertEquals(0.08f, d.poseOf(1).zFactor, EPS)
    }

    /**
     * 过渡起点必须**精确**落在旧槽位上：`t == 0f` 时 [SlotLayout.lerp] 逐比特还原 `from`。
     * 这条同时钉死了插值方向——origin/target 互换会让这里返回 `poseFor(0)`。
     */
    @Test
    fun `过渡刚开始时姿态精确落在起点槽位`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)

        assertTrue(d.isTransitioning)
        assertEquals(SlotLayout.poseFor(1), d.poseOf(0))
        assertNotEquals(SlotLayout.poseFor(0), d.poseOf(0))
        assertEquals(SlotLayout.poseFor(2), d.poseOf(1))
    }

    /** 多步推进的起点是 `slot + step` 而不是恒定的 `slot + 1`。 */
    @Test
    fun `多步推进时姿态从对应的远端槽位起步`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(3)

        assertEquals(SlotLayout.poseFor(3), d.poseOf(0))
        assertEquals(SlotLayout.poseFor(4), d.poseOf(1))
    }

    @Test
    fun `过渡结束后姿态精确落在目标槽位`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS + 0.1f)

        assertEquals(SlotLayout.poseFor(2).zFactor, d.poseOf(2).zFactor, EPS)
        // 四个字段全都要精确，不只是 z
        assertEquals(SlotLayout.poseFor(2), d.poseOf(2))
    }

    @Test
    fun `未过渡时 poseOf 精确等于槽位姿态`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        assertFalse(d.isTransitioning)
        for (slot in 0 until VISIBLE_SLOTS) {
            assertEquals("slot $slot", SlotLayout.poseFor(slot), d.poseOf(slot))
        }
    }

    /** 过渡进行中再点卡片必须被忽略，否则两段动画会打架。 */
    @Test
    fun `过渡进行中忽略新的推进请求`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS / 2f)
        val yearMid = d.snapshot.slots[0].entry!!.year
        val poseMid = d.poseOf(1)

        d.requestAdvance(2)

        assertEquals("内容不该再往前跳", yearMid, d.snapshot.slots[0].entry!!.year)
        assertEquals("过渡进度不该被打断重置", poseMid, d.poseOf(1))
    }

    @Test
    fun `step 为零或负数时忽略推进请求`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val before = d.snapshot.slots[0].entry!!.year

        d.requestAdvance(0)
        assertFalse(d.isTransitioning)
        assertEquals(before, d.snapshot.slots[0].entry!!.year)

        d.requestAdvance(-2)
        assertFalse(d.isTransitioning)
        assertEquals(before, d.snapshot.slots[0].entry!!.year)
    }

    @Test
    fun `空队列上 requestAdvance 不进入过渡`() {
        val (d, _) = driver()
        d.reset(emptyList(), 8, 17)

        d.requestAdvance(1)

        assertFalse(d.isTransitioning)
        assertTrue(d.snapshot.slots.all { it.entry == null })
        assertEquals(SlotLayout.poseFor(0), d.poseOf(0))
    }

    @Test
    fun `reset 中断进行中的过渡`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        assertTrue(d.isTransitioning)

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 18)

        assertFalse(d.isTransitioning)
        assertEquals(SlotLayout.poseFor(0), d.poseOf(0))
    }

    @Test
    fun `空数据时 tick 不崩溃且槽位全空`() {
        val (d, _) = driver()

        d.reset(emptyList(), 2, 30)
        d.tick(AUTO_ADVANCE_SECONDS * 3)

        assertEquals(VISIBLE_SLOTS, d.snapshot.slots.size)
        assertTrue(d.snapshot.slots.all { it.entry == null })
    }

    @Test
    fun `条目少于槽位数时多余槽位为空`() {
        val (d, _) = driver()

        d.reset(entries(1, 2, 3), 8, 17)

        assertEquals(3, d.snapshot.slots.count { it.entry != null })
        assertEquals(3, d.snapshot.slots.count { it.entry == null })
    }

    @Test
    fun `日期未变时 dayChanged 为假`() {
        val (d, _) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(AUTO_ADVANCE_SECONDS)

        assertFalse(d.dayChanged())
    }

    @Test
    fun `跨午夜后 dayChanged 为真`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 8, 18)

        assertTrue(d.dayChanged())
    }

    /**
     * `2026-08-17 -> 2027-08-17` 只有年份变了。这条专门堵住「只比 dayOfMonth」、
     * 「只比 month」、「比 dayOfYear」、「拿 clock 去比 snapshot.month/day」这几种
     * 写法——它们在 `跨午夜后 dayChanged 为真` 里全都能蒙混过关。
     */
    @Test
    fun `跨年但月日相同也算跨天`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2027, 8, 17)

        assertTrue(d.dayChanged())
    }

    /** 跨月但日号相同同样算跨天。 */
    @Test
    fun `跨月但日号相同也算跨天`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 9, 17)

        assertTrue(d.dayChanged())
    }

    /** 基准日期在每次 [QueueDriver.reset] 时重新捕获，而不是只在构造时取一次。 */
    @Test
    fun `reset 重新捕获日期基准`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 8, 18)
        assertTrue(d.dayChanged())

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 18)

        assertFalse("换日重载之后基准应跟上", d.dayChanged())
    }

    @Test
    fun `推进不改变快照的月日`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 3, 5)

        d.tick(AUTO_ADVANCE_SECONDS)
        assertEquals(3, d.snapshot.month)
        assertEquals(5, d.snapshot.day)

        d.tick(TRANSITION_SECONDS)
        d.requestAdvance(2)
        assertEquals(3, d.snapshot.month)
        assertEquals(5, d.snapshot.day)
    }

    @Test
    fun `快照槽位按 0 到 5 顺序编号且内容按队列顺序排列`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3), 8, 17)

        assertEquals((0 until VISIBLE_SLOTS).toList(), d.snapshot.slots.map { it.slot })

        // 三条内容应沿槽位按环形顺序前进（年份 1,2,3 环绕）
        val years = d.snapshot.slots.mapNotNull { it.entry?.year }
        assertEquals(3, years.size)
        assertEquals((years[0] % 3) + 1, years[1])
        assertEquals((years[1] % 3) + 1, years[2])
    }

    @Test
    fun `尚未 reset 时 tick 与 poseOf 都安全`() {
        val (d, _) = driver()

        d.tick(AUTO_ADVANCE_SECONDS * 2)

        assertFalse(d.isTransitioning)
        assertEquals(VISIBLE_SLOTS, d.snapshot.slots.size)
        assertTrue(d.snapshot.slots.all { it.entry == null })
        assertEquals(SlotLayout.poseFor(0), d.poseOf(0))
    }

    @Test
    fun `不同随机种子给出不同起点`() {
        val list = entries(*IntArray(100) { it + 1 })
        val starts =
            (0 until 20).map { seed ->
                val d = QueueDriver(clock = { LocalDate.of(2026, 8, 17) }, random = Random(seed))
                d.reset(list, 8, 17)
                d.snapshot.slots[0].entry!!.year
            }

        assertTrue("20 个种子应产生多于 1 个不同起点", starts.toSet().size > 1)
    }

    @Test
    fun `连续多次自动推进累计正确`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val start = d.snapshot.slots[0].entry!!.year

        repeat(3) {
            d.tick(AUTO_ADVANCE_SECONDS)
            d.tick(TRANSITION_SECONDS)
        }

        val expected = ((start - 1 + 3) % 8) + 1
        assertEquals(expected, d.snapshot.slots[0].entry!!.year)
    }
}
