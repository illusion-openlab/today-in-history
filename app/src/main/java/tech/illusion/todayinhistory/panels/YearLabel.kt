package tech.illusion.todayinhistory.panels

import kotlin.math.abs

/**
 * 把年份整数格式化成展示文本。
 *
 * 负数是公元前；天文纪年的 0 对应公元前 1 年，所以 0 也按公元前处理
 * （历法上不存在「公元 0 年」）。上游数据里未见 0，但解析器允许它通过，
 * 所以这里把它定义清楚而不是留成未定义行为。
 *
 * 取绝对值走 [Long]：`abs(Int.MIN_VALUE)` 在补码下会溢出回 `Int.MIN_VALUE` 自身，
 * 用 Int 取绝对值会渲染出「公元前 -2147483648 年」这种带负号的畸形文本。
 *
 * 这里刻意**不走 string 资源**：格式化是纯逻辑，不依赖 Android framework，
 * 因此能在 JVM 单测里直接验证（见 `YearLabelTest`）。类型标签那类需要本地化的
 * 文案才走 [labelOf] + `stringResource`。
 */
fun formatYear(year: Int): String =
    when {
        year > 0 -> "$year 年"
        year == 0 -> "公元前 1 年"
        else -> "公元前 ${abs(year.toLong())} 年"
    }
