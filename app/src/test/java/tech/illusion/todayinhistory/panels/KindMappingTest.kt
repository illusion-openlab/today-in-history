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
 * 所以这里同时钉两件事：
 * 1. **逐个映射到位**（拦「改错一个」和「两个对调」——对调仍然两两不等，只查不等是漏的）；
 * 2. **两两不等**（拦「两个塌成同一个值」）。
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
