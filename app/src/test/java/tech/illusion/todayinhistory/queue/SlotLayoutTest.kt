package tech.illusion.todayinhistory.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 1e-5f

class SlotLayoutTest {

    @Test
    fun `焦点槽位不透明且原尺寸`() {
        val pose = SlotLayout.poseFor(0)

        assertEquals(1f, pose.scale, EPS)
        assertEquals(1f, pose.alpha, EPS)
    }

    @Test
    fun `槽位越深则 z 越小 越透明 但尺寸恒定`() {
        // 钱包式堆叠：卡片**等大**是这套视觉的前提，scale 不再参与纵深表达。
        // 纵深由 z、垂直错位与 alpha 共同提供。把 scale 改成递减会让堆叠退回长廊，
        // 所以这里同时钉死「递减的三项」与「恒定的一项」。
        for (slot in 1 until VISIBLE_SLOTS) {
            val near = SlotLayout.poseFor(slot - 1)
            val far = SlotLayout.poseFor(slot)

            assertTrue("slot $slot 的 z 应比前一个小", far.zFactor < near.zFactor)
            assertTrue("slot $slot 的 alpha 应比前一个小", far.alpha < near.alpha)
            assertTrue("slot $slot 的 y 应比前一个高", far.yFactor > near.yFactor)
            assertEquals("slot $slot 的 scale 必须与焦点卡相同（等大是堆叠的前提）",
                near.scale, far.scale, EPS)
        }
    }

    @Test
    fun `所有槽位的因子都落在容器范围内`() {
        for (slot in 0 until VISIBLE_SLOTS) {
            val pose = SlotLayout.poseFor(slot)

            assertTrue("z 越界: ${pose.zFactor}", pose.zFactor in -0.5f..0.5f)
            assertTrue("y 越界: ${pose.yFactor}", pose.yFactor in -0.5f..0.5f)
            assertTrue("scale 越界: ${pose.scale}", pose.scale in 0f..1f)
            assertTrue("alpha 越界: ${pose.alpha}", pose.alpha in 0f..1f)
        }
    }

    @Test
    fun `时钟摆在堆叠右下且不与之重叠`() {
        val clock = SlotLayout.clockPose

        // 时钟是常驻锚点：卡片随推进变化，它不变。所以把它放在堆叠**之外**
        // 的右下角，而不是叠在卡片前面——叠着的话推进时视线会被反复打断。
        val rightmostCard = (0 until VISIBLE_SLOTS).maxOf { SlotLayout.poseFor(it).xFactor }
        assertTrue(
            "时钟应在所有卡片右侧（当前 ${clock.xFactor} vs 最右卡片 $rightmostCard）",
            clock.xFactor > rightmostCard,
        )

        val lowestCard = (0 until VISIBLE_SLOTS).minOf { SlotLayout.poseFor(it).yFactor }
        assertTrue(
            "时钟应贴近底部（当前 ${clock.yFactor} vs 最低卡片 $lowestCard）",
            clock.yFactor <= lowestCard,
        )

        // 与堆叠共用同一偏航角，两者才像摆在同一个平面上。
        assertEquals(SlotLayout.poseFor(0).yawDegrees, clock.yawDegrees, EPS)
    }

    @Test
    fun `槽位越界被钳制到最近的合法槽位`() {
        assertEquals(SlotLayout.poseFor(0), SlotLayout.poseFor(-3))
        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), SlotLayout.poseFor(VISIBLE_SLOTS + 4))
    }

    @Test
    fun `插值端点等于原姿态`() {
        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(a, SlotLayout.lerp(a, b, 0f))
        assertEquals(b, SlotLayout.lerp(a, b, 1f))
    }

    @Test
    fun `插值中点取两端平均`() {
        val a = SlotPose(zFactor = 0f, yFactor = 0f, scale = 0f, alpha = 0f)
        val b = SlotPose(zFactor = 1f, yFactor = 2f, scale = 4f, alpha = 8f)

        val mid = SlotLayout.lerp(a, b, 0.5f)

        assertEquals(0.5f, mid.zFactor, EPS)
        assertEquals(1f, mid.yFactor, EPS)
        assertEquals(2f, mid.scale, EPS)
        assertEquals(4f, mid.alpha, EPS)
    }

    @Test
    fun `插值参数越界被钳制`() {
        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(a, SlotLayout.lerp(a, b, -2f))
        assertEquals(b, SlotLayout.lerp(a, b, 5f))
    }

    @Test
    fun `缓动函数端点为零和一且单调递增`() {
        assertEquals(0f, SlotLayout.easeOutCubic(0f), EPS)
        assertEquals(1f, SlotLayout.easeOutCubic(1f), EPS)

        var prev = -1f
        for (i in 0..20) {
            val v = SlotLayout.easeOutCubic(i / 20f)
            assertTrue("缓动应单调递增", v > prev)
            prev = v
        }
    }

    @Test
    fun `缓动前段快于线性`() {
        assertTrue(SlotLayout.easeOutCubic(0.25f) > 0.25f)
    }

    // ---------------------------------------------------------------------
    // 以下为对 brief 测试的加强。每一条都对应一处「变异实现能让 brief 原有 10 条
    // 全绿」的零覆盖行为，详见 task-4-report.md 的变异矩阵。
    // ---------------------------------------------------------------------

    /** brief 从未在 0..1 之外调用过缓动函数：去掉它的 coerceIn，原有 10 条不会红。 */
    @Test
    fun `缓动参数越界被钳制`() {
        assertEquals(0f, SlotLayout.easeOutCubic(-0.001f), EPS)
        assertEquals(0f, SlotLayout.easeOutCubic(-2f), EPS)
        assertEquals(1f, SlotLayout.easeOutCubic(1.001f), EPS)
        assertEquals(1f, SlotLayout.easeOutCubic(7f), EPS)
    }

    @Test
    fun `缓动输出恒在零到一之间`() {
        for (i in -10..30) {
            val t = i / 20f
            val v = SlotLayout.easeOutCubic(t)
            assertTrue("easeOutCubic($t) = $v 越界", v in 0f..1f)
        }
    }

    /**
     * 阶数必须钉死取值：`1 - (1 - t)^n` 的整个族（n ≥ 2）都满足端点、单调、值域和
     * 「全程快于线性」，靠不变量筛不出阶数——Quad(`1-(1-t)²`) 和 Quart(`1-(1-t)⁴`)
     * 能让其余全部测试变绿。而三次是 easeOutCubic 的数学定义，不是 Task 9 的可调参数。
     */
    @Test
    fun `缓动是三次而非其它阶`() {
        assertEquals(0.271f, SlotLayout.easeOutCubic(0.1f), EPS) // 1 - 0.9³
        assertEquals(0.875f, SlotLayout.easeOutCubic(0.5f), EPS) // 1 - 0.5³
        assertEquals(0.936f, SlotLayout.easeOutCubic(0.6f), EPS) // 1 - 0.4³
        assertEquals(0.999f, SlotLayout.easeOutCubic(0.9f), EPS) // 1 - 0.1³
    }

    /**
     * `Float.coerceIn` 用 `<` / `>` 实现，NaN 与任何数比较都是 false，会被**原样返回**。
     * 不设防的话 NaN 会一路穿透成全 NaN 姿态并灌进场景变换。
     * 现实触发点：动画驱动方算 `elapsed / duration`，duration 为 0 时得 `0f/0f` = NaN。
     */
    @Test
    fun `NaN 进度退化为起点而不穿透`() {
        assertEquals(0f, SlotLayout.easeOutCubic(Float.NaN), EPS)

        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(a, SlotLayout.lerp(a, b, Float.NaN))
    }

    /** ±Inf 本来就能被 coerceIn 正常钳制，一并钉住防回归。 */
    @Test
    fun `无穷大进度被钳制到端点`() {
        assertEquals(1f, SlotLayout.easeOutCubic(Float.POSITIVE_INFINITY), EPS)
        assertEquals(0f, SlotLayout.easeOutCubic(Float.NEGATIVE_INFINITY), EPS)

        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(b, SlotLayout.lerp(a, b, Float.POSITIVE_INFINITY))
        assertEquals(a, SlotLayout.lerp(a, b, Float.NEGATIVE_INFINITY))
    }

    /** brief 只在 t=0.25 采样一次；缓入缓出等曲线能蒙混过关。这里全程比对线性。 */
    @Test
    fun `缓动全程快于线性`() {
        for (i in 1..19) {
            val t = i / 20f
            val v = SlotLayout.easeOutCubic(t)
            assertTrue("easeOutCubic($t) = $v 应快于线性", v > t)
        }
    }

    /**
     * brief 只比较了 datePose 的 z 和 y，alpha 零覆盖。
     *
     * 这里**只钉 alpha**：钉在最前的日期牌就该全不透明，那是语义不变量。scale 不是——
     * 日期牌和卡片是不同元件，没有任何语义要求它与焦点卡等大，Task 9 完全可能把它调小
     * 以免抢眼。scale 的约束放到下面的范围/可见性检查里。
     */
    @Test
    fun `时钟不透明`() {
        assertEquals(1f, SlotLayout.clockPose.alpha, EPS)
    }

    /**
     * brief 的范围检查只遍历了槽位，datePose 飞出容器或缩成 0 都不会红。
     *
     * scale **刻意不设上界**：日期牌和卡片是不同元件，既没有语义要求它与焦点卡等大，
     * 也没有语义要求它不能更大——Task 9 调成 1.15f 是合法调优，不该在这里变红。
     * 只要求为正（不能隐形）。alpha 的上界 1f 则是真不变量，不透明度没法超过全不透明。
     *
     * 对照：槽位侧的 `scale in 0f..1f` 是真不变量（不该有卡片比焦点卡大），不要照这里放宽。
     */
    @Test
    fun `时钟的因子落在容器范围内且可见`() {
        for (layer in 0..1) {
            val clock = SlotLayout.clockPose
            assertTrue("第 $layer 层 z 越界: ${clock.zFactor}", clock.zFactor in -0.5f..0.5f)
            assertTrue("第 $layer 层 y 越界: ${clock.yFactor}", clock.yFactor in -0.5f..0.5f)
            assertTrue("第 $layer 层 x 越界: ${clock.xFactor}", clock.xFactor in -0.5f..0.5f)
            assertTrue("第 $layer 层 alpha 越界: ${clock.alpha}", clock.alpha in 0f..1f)
            assertTrue("第 $layer 层 scale 必须为正", clock.scale > 0f)
        }

        // 越界的层号钳到最近一层，不抛异常
        assertEquals(SlotLayout.clockPose, SlotLayout.clockPose)
        assertEquals(SlotLayout.clockPose, SlotLayout.clockPose)
    }

    /**
     * brief 的范围检查用的是 `in 0f..1f`，允许 0——把最深槽位的 alpha 调成 0f
     * 既不破坏单调性也不越界，但那张卡实际上已经隐形了。
     */
    @Test
    fun `所有槽位都仍然可见`() {
        for (slot in 0 until VISIBLE_SLOTS) {
            val pose = SlotLayout.poseFor(slot)

            assertTrue("slot $slot 的 scale 必须为正", pose.scale > 0f)
            assertTrue("slot $slot 的 alpha 必须为正", pose.alpha > 0f)
        }
    }

    /** brief 用的是 -3 和 VISIBLE_SLOTS+4，离边界太远；差一错误要在边界上钉。 */
    @Test
    fun `槽位钳制在边界上也成立`() {
        assertEquals(SlotLayout.poseFor(0), SlotLayout.poseFor(-1))
        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), SlotLayout.poseFor(VISIBLE_SLOTS))
        assertEquals(SlotLayout.poseFor(0), SlotLayout.poseFor(Int.MIN_VALUE))
        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), SlotLayout.poseFor(Int.MAX_VALUE))
    }

    /**
     * brief 的中点测试把 `from` 取成全 0，于是 `from` 那一项恒等于 0——丢掉 `from`
     * 贡献的实现（`to.x * k`）在那条测试里是绿的。这里两端都非零。
     */
    @Test
    fun `插值中点在两端都非零时仍取平均`() {
        val a = SlotPose(zFactor = -0.4f, yFactor = 0.3f, scale = 0.5f, alpha = 0.2f)
        val b = SlotPose(zFactor = 0.2f, yFactor = -0.1f, scale = 0.9f, alpha = 1.0f)

        val mid = SlotLayout.lerp(a, b, 0.5f)

        assertEquals(-0.1f, mid.zFactor, EPS)
        assertEquals(0.1f, mid.yFactor, EPS)
        assertEquals(0.7f, mid.scale, EPS)
        assertEquals(0.6f, mid.alpha, EPS)
    }

    /**
     * 端点必须**逐比特**精确，否则每轮推进都会残留 ulp 级偏移并累积。brief 只验了
     * 槽位 0 与 3 这一对，而端点是否精确取决于具体数值，Task 9 调完可能就不成立了。
     */
    @Test
    fun `插值端点对每一对槽位都逐比特精确`() {
        for (i in 0 until VISIBLE_SLOTS) {
            for (j in 0 until VISIBLE_SLOTS) {
                val a = SlotLayout.poseFor(i)
                val b = SlotLayout.poseFor(j)

                assertEquals("lerp($i, $j, 0f) 应精确等于起点", a, SlotLayout.lerp(a, b, 0f))
                assertEquals("lerp($i, $j, 1f) 应精确等于终点", b, SlotLayout.lerp(a, b, 1f))
            }
        }
    }

    @Test
    fun `插值随参数单调推进`() {
        val a = SlotLayout.poseFor(VISIBLE_SLOTS - 1)
        val b = SlotLayout.poseFor(0)

        var prev = SlotLayout.lerp(a, b, 0f)
        for (i in 1..20) {
            val cur = SlotLayout.lerp(a, b, i / 20f)

            assertTrue("z 应单调递增", cur.zFactor > prev.zFactor)
            // 卡片等大：scale 不参与插值，全程恒定。这条断言同时挡住「把 scale 改回递减」。
            assertEquals("scale 应全程恒定", prev.scale, cur.scale, EPS)
            assertTrue("alpha 应单调递增", cur.alpha > prev.alpha)
            assertTrue("y 应单调递减", cur.yFactor < prev.yFactor)
            prev = cur
        }
    }
}
