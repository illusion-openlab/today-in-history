package tech.illusion.todayinhistory.queue

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // 非同义反复的独立核对：0.0050 * 0.125 + 0.0175 * 0.875 == 0.0159375
        // （数值来自钱包式堆叠的姿态表；改姿态表必须回来重算这一行，
        //  它是全测试里唯一不依赖 SlotLayout 自身实现的交叉验证）
        assertEquals(0.0159375f, d.poseOf(1).zFactor, EPS)
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
        // 只数个数分不清「空槽补在前面」还是「补在后面」，这里钉死位置
        assertTrue("前 3 槽必须有内容", d.snapshot.slots.subList(0, 3).all { it.entry != null })
        assertTrue("后 3 槽必须为空", d.snapshot.slots.subList(3, 6).all { it.entry == null })
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
     * 跨午夜的竞态：23:59:59.9 发起加载 8/17 的数据，00:00:00.1 才回调 `reset`。
     *
     * 若基准取自 `reset` 时刻的 `clock()`，基准会变成 8/18 而快照内容是 8/17，
     * `dayChanged()` 返回 false，接下来约 24 小时都不会再重载——日期牌和内容
     * 双双停在昨天。基准必须取自**快照的月日**，也就是「我实际装的是哪一天」。
     */
    @Test
    fun `快照日期与系统日期不一致时立刻要求重载`() {
        val (d, _) = driver(today = LocalDate.of(2026, 8, 18))

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        assertTrue("快照停在 8/17 而系统已是 8/18，必须重载", d.dayChanged())
    }

    /**
     * 「历史上的今天」按 `(月, 日)` 取数，年份不参与。2027-08-17 的内容与
     * 2026-08-17 完全相同，因此**不该**触发重载。
     *
     * 这条同时钉死「只比 month」「只比 dayOfMonth」以外的另一个方向：
     * 拿完整 `LocalDate` 去比会在这里误报重载。
     */
    @Test
    fun `跨年但月日相同不算跨天`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2027, 8, 17)

        assertFalse("月日没变，内容仍然有效，不该重载", d.dayChanged())
    }

    /** 跨月但日号相同算跨天——堵住「只比 dayOfMonth」。 */
    @Test
    fun `跨月但日号相同也算跨天`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 9, 17)

        assertTrue(d.dayChanged())
    }

    /** 重载到新的一天之后，[QueueDriver.dayChanged] 应立刻复位。 */
    @Test
    fun `重载到新的一天后 dayChanged 复位`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 8, 18)
        assertTrue(d.dayChanged())

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 18)

        assertFalse("换日重载之后基准应跟上", d.dayChanged())
    }

    /** 首次 [QueueDriver.reset] 之前快照是哨兵日期，必须表现为「需要加载」。 */
    @Test
    fun `首次 reset 之前 dayChanged 为真`() {
        val (d, _) = driver()

        assertTrue(d.dayChanged())
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
        // 哨兵日期必须是明显非法值，不能是会被渲染成「1月1日」的假日期
        assertEquals(0, d.snapshot.month)
        assertEquals(0, d.snapshot.day)
    }

    /**
     * **单帧 NaN 会让自动推进永久失效**，这是本驱动器最危险的一条路径。
     *
     * `idleSeconds` 一旦被 `+= NaN` 污染就没有任何自愈路径：`NaN >= 300f` 为 false
     * 所以永远不再触发自动推进，而只有 `reset()` 和用户点击的 `requestAdvance()`
     * 才会把它清零。对一个「打开就放着自己翻」的摆件，等于核心功能静默死掉，
     * 最长要熬到下一次跨午夜重载。
     *
     * 注意 `secondsUntilAutoAdvance()` 也救不了：`coerceAtLeast` 是
     * `if (this < min) min else this`，而 `NaN < 0f` 为 false，NaN 原样透出。
     */
    @Test
    fun `单帧 NaN 的 deltaSeconds 不会让自动推进永久失效`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val start = d.snapshot.slots[0].entry!!.year
        val next = d.snapshot.slots[1].entry!!.year

        d.tick(Float.NaN)

        assertEquals("NaN 帧不该污染空闲计时", AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)

        d.tick(AUTO_ADVANCE_SECONDS)

        assertNotEquals("NaN 之后自动推进仍须工作", start, d.snapshot.slots[0].entry!!.year)
        assertEquals(next, d.snapshot.slots[0].entry!!.year)
    }

    /** 无穷大与负数 deltaSeconds 同样是非法输入，一律忽略。 */
    @Test
    fun `无穷大与负数 deltaSeconds 被忽略`() {
        for (bad in listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -5f)) {
            val (d, _) = driver()
            d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
            val start = d.snapshot.slots[0].entry!!.year

            d.tick(bad)

            assertEquals("delta=$bad", AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)
            assertEquals("delta=$bad 不该推进", start, d.snapshot.slots[0].entry!!.year)
            assertFalse("delta=$bad", d.isTransitioning)

            // 非法帧之后仍能正常推进
            d.tick(AUTO_ADVANCE_SECONDS)
            assertNotEquals("delta=$bad 之后应恢复正常", start, d.snapshot.slots[0].entry!!.year)
        }
    }

    /**
     * 过渡分支同样要挡住 NaN。
     *
     * 注意这条与 `单帧 NaN...` 覆盖的是**两个不同的字段**：这里是 `transitionSeconds`，
     * 那里是 `idleSeconds`。两者的失效方式也不同——`transitionSeconds` 变 NaN 会让
     * `isTransitioning`（`NaN >= 0f` 为 false）直接掉出过渡态、动画被吃掉；
     * `idleSeconds` 变 NaN 则是永久卡死。别拿一条的结论去推另一条。
     */
    @Test
    fun `过渡期间的 NaN deltaSeconds 不会打断过渡`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(Float.NaN)

        assertTrue("NaN 帧不该把过渡吃掉", d.isTransitioning)
        assertEquals("进度不该被污染", SlotLayout.poseFor(1), d.poseOf(0))

        d.tick(TRANSITION_SECONDS)

        assertFalse(d.isTransitioning)
        assertEquals(SlotLayout.poseFor(0), d.poseOf(0))
    }

    /**
     * 过渡分支对四种非法 delta 一视同仁。
     *
     * 这条与 `无穷大与负数 deltaSeconds 被忽略` 是**对称**的一对：那条覆盖空闲分支，
     * 这条覆盖过渡分支。之所以要补齐对称性——`idleSeconds` 那个永久失效的洞，
     * 起因正是当初只探测了过渡分支就以为全都安全。
     */
    @Test
    fun `过渡期间的各种非法 deltaSeconds 都不打断过渡`() {
        for (bad in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -5f)) {
            val (d, _) = driver()
            d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

            d.requestAdvance(1)
            d.tick(bad)

            assertTrue("delta=$bad 不该把过渡吃掉", d.isTransitioning)
            assertEquals("delta=$bad 进度不该被污染", SlotLayout.poseFor(1), d.poseOf(0))

            // 非法帧之后过渡仍能正常走完
            d.tick(TRANSITION_SECONDS)
            assertFalse("delta=$bad", d.isTransitioning)
            assertEquals("delta=$bad", SlotLayout.poseFor(0), d.poseOf(0))
        }
    }

    /**
     * 6 槽模型的**端点退化**，Task 6 必须知道：`poseFor` 把越界槽位钳到 0..5，
     * 于是 step=1 时 `poseOf(5)` 的起点 `poseFor(6)` 被钳成 `poseFor(5)`，
     * 与目标相同——尾部新进的卡**原地不动**，没有滑入动画。
     */
    @Test
    fun `尾部槽位因钳制而原地不动`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        assertEquals(SlotLayout.poseFor(5), d.poseOf(5))

        d.tick(TRANSITION_SECONDS / 2f)
        assertEquals("过渡中途同样纹丝不动", SlotLayout.poseFor(5).zFactor, d.poseOf(5).zFactor, EPS)

        d.tick(TRANSITION_SECONDS)
        assertEquals(SlotLayout.poseFor(5), d.poseOf(5))
    }

    /** step=3 时尾部三张卡全部从 `poseFor(5)` 起步——退化范围随 step 变大。 */
    @Test
    fun `多步推进时尾部多张卡挤在同一起点`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(3)

        assertEquals(SlotLayout.poseFor(5), d.poseOf(3))
        assertEquals(SlotLayout.poseFor(5), d.poseOf(4))
        assertEquals(SlotLayout.poseFor(5), d.poseOf(5))
    }

    /** 越界槽位号在过渡内外都被钳制，不抛异常。 */
    @Test
    fun `越界槽位号被钳制`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), d.poseOf(VISIBLE_SLOTS))
        assertEquals(SlotLayout.poseFor(0), d.poseOf(-1))

        d.requestAdvance(1)

        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), d.poseOf(VISIBLE_SLOTS))
        assertEquals(SlotLayout.poseFor(0), d.poseOf(-1))
    }

    /**
     * 起点必须**逐位**取决于注入的 [Random]，不能只断言「结果有多样性」。
     *
     * 「20 个种子产生多于 1 个不同起点」是个假断言：把
     * `randomStart(entries, random)` 换成 `randomStart(entries, Random.Default)`
     * ——也就是完全忽略注入的 random——100 条数据抽 20 次几乎必然出现多个不同值，
     * 断言照样绿。`HistoryQueueTest` 也兜不住：那边测的是 `randomStart` 自身，
     * 这里要测的是 driver **有没有把 random 正确传下去**。
     *
     * 同一个坑在 Task 3 已经踩过一次（见 HistoryQueueTest 对 randomStart 复现性的注释），
     * 这里逐位钉死，不再依赖多样性。
     */
    @Test
    fun `起点逐位取决于注入的 random`() {
        val list = entries(*IntArray(100) { it + 1 })
        val starts =
            (0 until 20).map { seed ->
                val d = QueueDriver(clock = { LocalDate.of(2026, 8, 17) }, random = Random(seed))
                d.reset(list, 8, 17)
                d.snapshot.slots[0].entry!!.year
            }

        val expected =
            (0 until 20).map { seed -> HistoryQueue.randomStart(list, Random(seed)).entryAt(0)!!.year }
        assertEquals(expected, starts)

        // 附带确认这组期望值本身不是常量（否则上面的相等断言会退化成同义反复）
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

    @Test
    fun `离场条目是推进前的焦点条目`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val before = d.snapshot.slots[0].entry!!.year

        d.requestAdvance(1)

        // 它已经不在 slots 里了，但仍需被显示一个过渡时长
        assertEquals(before, d.exitingEntry()!!.year)
        assertEquals(before, d.snapshot.exiting!!.year)
        assertTrue(d.snapshot.slots.none { it.entry?.year == before })
    }

    @Test
    fun `非过渡期没有离场条目`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        assertNull(d.exitingEntry())

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS + 0.1f)

        assertNull("过渡结束后离场卡必须消失", d.exitingEntry())
    }

    @Test
    fun `离场姿态从焦点槽位滑向离场点`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        // 静止时压在离场点且完全透明——不需要额外的显隐开关
        assertEquals(SlotLayout.exitPose, d.exitPoseNow())
        assertEquals(0f, d.exitPoseNow().alpha, EPS)

        d.requestAdvance(1)
        d.tick(0f)
        // 起点精确等于焦点槽位
        assertEquals(SlotLayout.poseFor(0), d.exitPoseNow())

        d.tick(TRANSITION_SECONDS / 2f)
        val mid = d.exitPoseNow()
        assertTrue("应已向左移动", mid.xFactor < SlotLayout.poseFor(0).xFactor)
        assertTrue("应已开始变淡", mid.alpha < 1f)
        assertTrue("不应越过离场点", mid.xFactor > SlotLayout.exitPose.xFactor)

        d.tick(TRANSITION_SECONDS)
        assertEquals(SlotLayout.exitPose, d.exitPoseNow())
    }

    @Test
    fun `reset 清除离场条目`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        d.requestAdvance(1)
        assertNotNull(d.exitingEntry())

        d.reset(entries(9, 10, 11, 12, 13, 14, 15, 16), 8, 18)

        assertNull("换天后不该还留着上一天的离场卡", d.exitingEntry())
        assertNull(d.snapshot.exiting)
    }
}
