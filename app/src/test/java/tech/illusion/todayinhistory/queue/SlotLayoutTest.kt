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
    fun `槽位越深则 z 越小 尺寸越小 越透明`() {
        for (slot in 1 until VISIBLE_SLOTS) {
            val near = SlotLayout.poseFor(slot - 1)
            val far = SlotLayout.poseFor(slot)

            assertTrue("slot $slot 的 z 应比前一个小", far.zFactor < near.zFactor)
            assertTrue("slot $slot 的 scale 应比前一个小", far.scale < near.scale)
            assertTrue("slot $slot 的 alpha 应比前一个小", far.alpha < near.alpha)
            assertTrue("slot $slot 的 y 应比前一个高", far.yFactor > near.yFactor)
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
    fun `日期牌在焦点卡前方且更高`() {
        val date = SlotLayout.datePose
        val focus = SlotLayout.poseFor(0)

        assertTrue(date.zFactor > focus.zFactor)
        assertTrue(date.yFactor > focus.yFactor)
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

    /** brief 只在 t=0.25 采样一次；缓入缓出等曲线能蒙混过关。这里全程比对线性。 */
    @Test
    fun `缓动全程快于线性`() {
        for (i in 1..19) {
            val t = i / 20f
            val v = SlotLayout.easeOutCubic(t)
            assertTrue("easeOutCubic($t) = $v 应快于线性", v > t)
        }
    }

    /** brief 只比较了 datePose 的 z 和 y，scale/alpha 零覆盖。 */
    @Test
    fun `日期牌不透明且原尺寸`() {
        assertEquals(1f, SlotLayout.datePose.scale, EPS)
        assertEquals(1f, SlotLayout.datePose.alpha, EPS)
    }

    /** brief 的范围检查只遍历了槽位，datePose 飞出容器也不会红。 */
    @Test
    fun `日期牌的因子也落在容器范围内`() {
        val date = SlotLayout.datePose

        assertTrue("z 越界: ${date.zFactor}", date.zFactor in -0.5f..0.5f)
        assertTrue("y 越界: ${date.yFactor}", date.yFactor in -0.5f..0.5f)
        assertTrue("scale 越界: ${date.scale}", date.scale in 0f..1f)
        assertTrue("alpha 越界: ${date.alpha}", date.alpha in 0f..1f)
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
            assertTrue("scale 应单调递增", cur.scale > prev.scale)
            assertTrue("alpha 应单调递增", cur.alpha > prev.alpha)
            assertTrue("y 应单调递减", cur.yFactor < prev.yFactor)
            prev = cur
        }
    }
}
