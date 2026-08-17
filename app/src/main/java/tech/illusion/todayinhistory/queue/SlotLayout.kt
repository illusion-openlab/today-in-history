package tech.illusion.todayinhistory.queue

/**
 * 一个槽位的姿态，全部表达为**相对容器包围盒的比例因子**，不是米。
 *
 * 运行时由 HomeVolume 拿到 SpatialView 的实际尺寸（米）后相乘得到真实坐标，
 * 这样容器被用户缩放时布局自动跟随。
 *
 * @param zFactor 深度方向占比，正值朝向观者（SpatialView 坐标系 +Z 朝观者）
 * @param yFactor 垂直方向占比，正值向上
 */
data class SlotPose(
    val zFactor: Float,
    val yFactor: Float,
    val scale: Float,
    val alpha: Float,
)

object SlotLayout {

    /** 日期牌：钉在最前上方，不参与队列推进。 */
    val datePose = SlotPose(zFactor = 0.45f, yFactor = 0.34f, scale = 1.00f, alpha = 1.00f)

    /**
     * 槽位 0 是焦点卡，1..5 是待读条。
     *
     * 注意：容器 worldscaletype=2（Fixed），远处内容本已因透视自然缩小，
     * 这里的 scale 递减是叠加在其之上的。这组数值是起点，需在设备上调优（Task 9）。
     *
     * 调优时零值一律写 `0.0f`，**不要写 `-0.0f`**：[lerp] 在 `k == 0f` 端算的是
     * `from * 1f + to * 0f`，会把 `-0.0f` 归一成 `+0.0f`；而 data class 的 equals 对 Float
     * 走 `Float.compare`，`-0.0f != 0.0f`——端点逐比特精确的契约会因此静默失效。
     */
    private val poses =
        listOf(
            SlotPose(zFactor = 0.30f, yFactor = -0.12f, scale = 1.00f, alpha = 1.00f),
            SlotPose(zFactor = 0.10f, yFactor = 0.02f, scale = 0.72f, alpha = 0.85f),
            SlotPose(zFactor = -0.06f, yFactor = 0.11f, scale = 0.62f, alpha = 0.65f),
            SlotPose(zFactor = -0.20f, yFactor = 0.18f, scale = 0.54f, alpha = 0.48f),
            SlotPose(zFactor = -0.32f, yFactor = 0.24f, scale = 0.47f, alpha = 0.34f),
            SlotPose(zFactor = -0.42f, yFactor = 0.29f, scale = 0.42f, alpha = 0.22f),
        )

    init {
        require(poses.size == VISIBLE_SLOTS) { "姿态表必须覆盖全部 $VISIBLE_SLOTS 个槽位" }
    }

    /** 越界的槽位号被钳制到最近的合法槽位。 */
    fun poseFor(slot: Int): SlotPose = poses[slot.coerceIn(0, VISIBLE_SLOTS - 1)]

    /**
     * 姿态插值，[t] 在 0..1 之外会被钳制。
     *
     * 这里刻意用 `from * (1 - k) + to * k`，而不是更常见的 `from + (to - from) * k`。
     * 后者在 Float 精度下**端点不精确**：`k == 1f` 时 `0.30f + (-0.20f - 0.30f)` 得到
     * -0.19999999 而不是 -0.2f，`1.00f + (0.48f - 1.00f)` 得到 0.48000002 而不是 0.48f。
     * 混合形式则在两端都精确——`k == 0f` 时是 `from * 1 + to * 0`，`k == 1f` 时是
     * `from * 0 + to * 1`，都还原成原值的比特位。
     *
     * 这对本应用是硬要求：卡片每 5 分钟推进一次，动画结束时若不能精确落在目标姿态上，
     * 每一轮都会残留几个 ulp 的偏移并逐轮累积；而且下一轮动画的起点会是这个脏值，
     * 「当前姿态 == 某个槽位姿态」的相等判断也会失效。
     *
     * 代价是混合形式在区间中段理论上可能溢出 [from, to] 约 1 ulp。对比例因子而言这个
     * 量级毫无影响，端点精确才是要保的性质。
     */
    fun lerp(from: SlotPose, to: SlotPose, t: Float): SlotPose {
        val k = normalizeProgress(t)
        val inv = 1f - k
        return SlotPose(
            zFactor = from.zFactor * inv + to.zFactor * k,
            yFactor = from.yFactor * inv + to.yFactor * k,
            scale = from.scale * inv + to.scale * k,
            alpha = from.alpha * inv + to.alpha * k,
        )
    }

    /**
     * 三次缓出 `1 - (1 - t)^3`：起步快、收尾稳，卡片推进时观感最自然。
     *
     * [t] 经 [normalizeProgress] 归一，越界与 NaN 都不会穿透。
     *
     * 「三次」是本函数的**数学定义**，不是 Task 9 的可调参数：`1 - (1 - t)^n` 的整个族
     * （n ≥ 2）都同样满足端点为 0/1、单调递增、值域 [0,1] 和「全程快于线性」，靠这些不变量
     * 一个都筛不出阶数——Quad 和 Quart 能让其余全部测试变绿。所以阶数由
     * `缓动是三次而非其它阶` 直接钉死取值。要换缓动曲线请换函数名，别改这里的指数。
     */
    fun easeOutCubic(t: Float): Float {
        val k = normalizeProgress(t)
        val inv = 1f - k
        return 1f - inv * inv * inv
    }

    /**
     * 把任意 Float 归一到 0..1，是 [lerp] 与 [easeOutCubic] 唯一的进度入口。
     *
     * 单纯的 `coerceIn` 挡不住 NaN：它内部用 `<` / `>` 比较，而 **NaN 与任何数比较都是
     * false，于是被原样返回**。不加这层的话 `easeOutCubic(NaN)` 得到 NaN、`lerp(a, b, NaN)`
     * 得到四个字段全 NaN 的姿态，随后被灌进场景变换——实体会消失或跑到垃圾位置，而且现场
     * 离出错点很远，属于极难定位的一类故障。
     *
     * 触发场景很现实：动画驱动方算 `elapsed / duration`，`duration == 0` 时得到 `0f / 0f`
     * 即 NaN。（±Inf 反而安全，会被正常钳到 1f / 0f。）
     *
     * NaN 归到 0f 而不是 1f：0 是「没有进度」这个恒等元，与 `k == 0f` 的语义一致，组合起来
     * 也自洽——`easeOutCubic(NaN)` 得 0f，再喂给 [lerp] 同样得到 `from`。时长为 0 的动画本就
     * 不该走插值，调用方应自己直接置为终态。
     */
    private fun normalizeProgress(t: Float): Float = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
}
