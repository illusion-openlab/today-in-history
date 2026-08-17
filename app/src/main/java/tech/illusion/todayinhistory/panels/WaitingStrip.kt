package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.todayinhistory.data.HistoryEntry

/**
 * 待读条：队列 slot 1..5，只有年份和单行正文，点击可直接跳到它。
 *
 * 材质用 [Material.Regular] 而非焦点卡的 `Thick`：待读条不承担正文可读性，
 * 更轻的材质让它在纵深上明确退到焦点卡后面。
 *
 * 正文 `maxLines = 1` + 省略号是**设计意图**，不是截断妥协——待读条的职责是
 * 让人一眼判断「要不要现在看这条」，展开是焦点卡的事。
 *
 * ### alpha 由调用方给
 *
 * 「越远越淡」这条规则在这里落地：调用方（Task 8）传
 * `SlotLayout.poseFor(slot).alpha`，slot 1..5 依次为 0.85 / 0.65 / 0.48 / 0.34 / 0.22。
 * Task 6 的 `HistoryQueueSystem` 有意不在 ECS 侧写 alpha（见其 KDoc
 * 「为什么这里不写 alpha」），因为实体级不透明度要另一套材质 API 且会引入每帧开销。
 *
 * **按槽位静态应用，不是每帧插值。** 过渡的 0.6 秒里每张条保持自己槽位的不透明度，
 * 到位后跳变而非渐变。取舍原因同 [FocusCard]：插值意味着过渡期每帧重组 Compose，
 * 正是本设计要避免的。
 *
 * @param entry 该槽位的条目；为 null 表示队列不足以填满这个槽位，此时整条不渲染。
 * @param alpha 槽位不透明度，取自 `SlotLayout.poseFor(slot).alpha`。
 * @param onClick 点击整条时触发，用于把队列直接推进到这一条。
 */
@Composable
fun WaitingStrip(entry: HistoryEntry?, alpha: Float, onClick: () -> Unit) {
    if (entry == null) return

    PicoTheme {
        Row(
            modifier =
                // `this.` 不能省，理由见 FocusCard 同处注释。
                Modifier.graphicsLayer { this.alpha = alpha }
                    .width(520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .backgroundMaterial(true, Material.Regular)
                    .clickable { onClick() }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatYear(entry.year),
                style = PicoTheme.typography.labelLarge,
                color = colorOf(entry.kind),
            )
            Text(
                text = entry.text,
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
