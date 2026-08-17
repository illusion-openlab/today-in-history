package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import tech.illusion.todayinhistory.R

/**
 * 日期牌：钉在队列最前方上侧，显示「8月17日」。
 *
 * 它**不参与队列推进**——`SlotLayout.datePose` 是一个独立的固定姿态，
 * 不在 `poses[0..5]` 里，所以这里也没有 `alpha` 参数：日期牌恒为不透明
 * （`datePose.alpha == 1.0f`），它是当天的锚点标题而不是队列成员。
 *
 * 材质用 [Material.Regular]（基础层），比焦点卡的 `Thick` 轻，
 * 让纵深上「日期牌 < 焦点卡」的主次关系成立。
 */
@Composable
fun DatePanel(month: Int, day: Int) {
    PicoTheme {
        Box(
            modifier =
                Modifier.padding(4.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .backgroundMaterial(true, Material.Regular)
                    .padding(horizontal = 40.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.date_format, month, day),
                style = PicoTheme.typography.headlineLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
        }
    }
}
