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
     * 负数段用历史纪年（`-1` = 公元前 1 年），0 段借天文纪年（`0` = 公元前 1 年），
     * 两套约定的交界处必然重合。这不是 bug，是 `formatYear` KDoc 里写明的取舍；
     * 钉住它是为了让「有人把 0 改成别的输出」或「把负数段改成天文纪年」时立刻打红。
     */
    @Test
    fun `负一年与零年故意渲染成同一串文本`() {
        assertEquals("公元前 1 年", formatYear(-1))
        assertEquals(formatYear(0), formatYear(-1))
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
