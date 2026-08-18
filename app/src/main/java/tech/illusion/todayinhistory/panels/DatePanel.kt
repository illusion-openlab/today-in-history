package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import java.time.DayOfWeek
import tech.illusion.todayinhistory.R

/** 星期几对应的字符串资源。与 [labelOf] 同理：用 `when` 穷尽，新增分支由编译器保。 */
fun weekdayOf(day: DayOfWeek): Int =
    when (day) {
        DayOfWeek.MONDAY -> R.string.weekday_mon
        DayOfWeek.TUESDAY -> R.string.weekday_tue
        DayOfWeek.WEDNESDAY -> R.string.weekday_wed
        DayOfWeek.THURSDAY -> R.string.weekday_thu
        DayOfWeek.FRIDAY -> R.string.weekday_fri
        DayOfWeek.SATURDAY -> R.string.weekday_sat
        DayOfWeek.SUNDAY -> R.string.weekday_sun
    }

/** 时钟组第 0 层：时间。字号最大，站在最前。 */
@Composable
fun TimePanel(hour: Int, minute: Int, nudgeDp: Int) {
    PicoTheme {
        Box(
            modifier =
                Modifier.width(300.dp + nudgeDp.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .backgroundMaterial(true, Material.Thick)
                    .padding(horizontal = 36.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.time_format, hour, minute),
                style = PicoTheme.typography.displayMedium,
                color = PicoTheme.colorScheme.labelPrimary,
            )
        }
    }
}

/**
 * 时钟组第 1 层：日期与星期。
 *
 * 略靠后、略右偏，与 [TimePanel] 在深度上拉开一点——这是 Volume 才有的表达。
 *
 * @param nudgeDp 见 [HistoryCard] 的同名参数：**删掉它时间就不会走了**。
 *   时间每分钟变一次，但 `14:32` 与 `14:33` 的测量宽度完全相同，
 *   而 SDK 只在尺寸变化时才推送新像素。
 */
@Composable
fun DatePanel(month: Int, day: Int, weekday: DayOfWeek, nudgeDp: Int) {
    PicoTheme {
        Column(
            modifier =
                Modifier.width(260.dp + nudgeDp.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(true, Material.Regular)
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.date_format, month, day),
                style = PicoTheme.typography.titleLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Text(
                text = stringResource(weekdayOf(weekday)),
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        }
    }
}
