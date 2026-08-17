package tech.illusion.todayinhistory.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.todayinhistory.R
import tech.illusion.todayinhistory.data.Kind

/**
 * `colorOf` / `labelOf` 的映射测试。
 *
 * 这两个函数的错法不会被编译器、也不会被其它测试拦住：`when` 对 enum 穷尽只保证
 * **每个分支都写了**，不保证**写对了**。把 `Kind.DEATH` 映到 `R.string.kind_birth`
 * 照样编译通过、其余测试全绿、零 warning，然后每一条逝世记录都显示「出生」——
 * 这是内容正确性 bug，不是样式问题。
 *
 * 颜色同理：BIRTH 与 DEATH 拿到同一个色值也全绿，而那个 10dp 色点在 slot 3
 * （alpha 0.48）之后本来就难分辨，正是人眼评审最容易漏掉的一类错误。
 *
 * 所以这里同时钉两件事，但**两者的分工不是「各拦一半错误」**：
 *
 * 1. **逐个映射到位** —— 对当前这三个 `Kind` 而言，它在语义上**已经覆盖了**
 *    「两个塌成同一个值」的全部情形：三条都钉死了具体取值，任何塌陷或对调
 *    都必然让其中至少一条不成立。所以就现状而言，这一组是完备的。
 * 2. **两两不等 + [Kind.entries] 尺寸断言** —— 留着不是为了补第 1 组的漏，
 *    而是为了**枚举增长的方向**：将来新增 `Kind.ANNIVERSARY` 并（复制粘贴地）
 *    映到 `R.string.kind_event` 时，上面三条逐个映射**依然全绿**，
 *    只有「两两不等」和 `assertEquals(3, Kind.entries.size)` 会打红，
 *    提醒新类型必须同步这两个映射。
 *
 * 换句话说：第 1 组防**改错现有的**，第 2 组防**加了新的却没接上**。
 *
 * 注意本类断言全部作用于**资源 id**，`assertEquals(R.string.kind_death, labelOf(Kind.DEATH))`
 * 两侧引用同一个符号，对**文案内容**是恒真式 —— 文案本身由 `KindStringsTest` 覆盖。
 */
class KindMappingTest {

    @Test
    fun `labelOf 逐个映射到对应的字符串资源`() {
        assertEquals(R.string.kind_event, labelOf(Kind.EVENT))
        assertEquals(R.string.kind_birth, labelOf(Kind.BIRTH))
        assertEquals(R.string.kind_death, labelOf(Kind.DEATH))
    }

    @Test
    fun `labelOf 三种类型两两不等`() {
        val ids = Kind.entries.map { labelOf(it) }
        assertEquals("每个 Kind 都要有独立的标签资源", Kind.entries.size, ids.toSet().size)
    }

    @Test
    fun `labelOf 覆盖全部 Kind 且返回有效资源 id`() {
        assertEquals(3, Kind.entries.size) // 新增 Kind 时本条会失败，提醒同步这两个映射
        Kind.entries.forEach { kind ->
            assertNotEquals("labelOf($kind) 不能返回 0", 0, labelOf(kind))
        }
    }

    @Test
    fun `colorOf 逐个映射到约定色值`() {
        assertEquals(0xFF7B95FFuL, colorOf(Kind.EVENT).value shr 32)
        assertEquals(0xFF5AC88CuL, colorOf(Kind.BIRTH).value shr 32)
        assertEquals(0xFFBE82E6uL, colorOf(Kind.DEATH).value shr 32)
    }

    @Test
    fun `colorOf 三种类型两两不等`() {
        val colors = Kind.entries.map { colorOf(it) }
        assertEquals("每个 Kind 都要有可区分的强调色", Kind.entries.size, colors.toSet().size)
    }

    @Test
    fun `colorOf 覆盖全部 Kind 且都是完全不透明`() {
        Kind.entries.forEach { kind ->
            // 色点靠 alpha 通道显形；半透明会让它在毛玻璃上更难分辨。
            assertEquals("colorOf($kind) 必须完全不透明", 1f, colorOf(kind).alpha, 0f)
            assertTrue("colorOf($kind) 不能是透明色", colorOf(kind).value != 0uL)
        }
    }
}
