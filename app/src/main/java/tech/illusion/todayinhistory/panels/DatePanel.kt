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

/**
 * 时钟面板：时间、日期、星期合成一块，摆在容器右下角。
 *
 * @param nudgeDp **不是样式参数，删掉时间就不会走了。**
 *   时间每分钟变一次，但 `14:32` 与 `14:33` 的测量宽度完全相同，
 *   而 SDK 只在面板**测量尺寸变化**时才把重绘后的像素推送到空间表面——
 *   不加这个抖动，时间会永远停在启动那一刻。
 */
@Composable
fun ClockPanel(
    hour: Int,
    minute: Int,
    month: Int,
    day: Int,
    weekday: DayOfWeek,
    nudgeDp: Int,
) {
    PicoTheme {
        Column(
            modifier =
                Modifier.width(280.dp + nudgeDp.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(true, Material.Thick)
                    .padding(horizontal = 32.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.time_format, hour, minute),
                style = PicoTheme.typography.displayMedium,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Text(
                text =
                    stringResource(R.string.date_format, month, day) +
                        "  " +
                        stringResource(weekdayOf(weekday)),
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        }
    }
}
