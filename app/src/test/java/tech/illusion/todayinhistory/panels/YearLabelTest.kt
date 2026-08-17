package tech.illusion.todayinhistory.panels

import org.junit.Assert.assertEquals
import org.junit.Test

class YearLabelTest {

    @Test
    fun `公元后年份直接加年字`() {
        assertEquals("1945 年", formatYear(1945))
        assertEquals("1 年", formatYear(1))
    }

    @Test
    fun `公元前年份取绝对值并加前缀`() {
        assertEquals("公元前 1046 年", formatYear(-1046))
        assertEquals("公元前 4713 年", formatYear(-4713))
    }

    @Test
    fun `零年按公元前处理`() {
        assertEquals("公元前 1 年", formatYear(0))
    }

    /**
     * `abs(Int.MIN_VALUE)` 会溢出回 `Int.MIN_VALUE` 自身（补码没有对应的正数），
     * 于是朴素实现会渲染出「公元前 -2147483648 年」这种带负号的文本。
     * 数据里不会出现这个值，但格式化函数不该对任何 Int 输出畸形字符串。
     */
    @Test
    fun `最小整数不因取绝对值溢出而渲染出负号`() {
        val text = formatYear(Int.MIN_VALUE)
        assertEquals("公元前 2147483648 年", text)
    }
}
