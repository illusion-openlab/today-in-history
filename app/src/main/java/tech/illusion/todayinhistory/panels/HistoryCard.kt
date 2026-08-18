package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.todayinhistory.R
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

/**
 * 三种条目类型的强调色。
 *
 * 这三个值是**实色**，用于色点和年份文字；不要拿它们去喂
 * [com.pico.spatial.ui.foundation.material.backgroundMaterial]——那个 modifier
 * 画的是毛玻璃材质，不接受颜色参数，色点会退化成一块认不出类型的模糊方块。
 */
fun colorOf(kind: Kind): Color =
    when (kind) {
        Kind.EVENT -> Color(0xFF7B95FF)
        Kind.BIRTH -> Color(0xFF5AC88C)
        Kind.DEATH -> Color(0xFFBE82E6)
    }

/** 卡片宽度。正文可用宽度 = 560 − 36×2 = 488dp。 */
private val CardWidth = 560.dp

/** 卡片左右内边距，与 [CardWidth] 一起决定每行容纳的字数。 */
private val CardPaddingHorizontal = 36.dp

/**
 * 正文最大行数。
 *
 * 钱包式堆叠要求**六张卡等大**，所以正文必须定行数而不能自适应高度。
 * 取 4 行的依据是全库实测（正文可用宽 488dp，16sp 中文 ≈ 16dp/字 ⇒ 每行约 30 字）：
 * 4 行 ≈ 120 字，54018 条里超出的只有 **80 条 = 0.15%**；中位仅 22 字（连一行都不到）。
 * 再放宽到 5 行只多救 53 条，却会让卡片明显变高、堆叠更占空间，不划算。
 *
 * 被截断的那 0.15% 走 [TextOverflow.Ellipsis]，不会无声切断。
 */
private const val MaxBodyLines = 4

/**
 * 卡片固定高度。
 *
 * 等大是钱包式堆叠的前提：一旦高度随内容变化，堆叠会变成参差的阶梯。
 * 240dp = 内边距 30×2 + 类型行 20 + 年份 50 + 间距 14×2 + 正文 4×24。
 *
 * ⚠️ **改这个值必须同时重算 [MaxBodyLines]**，两者对不上时正文会在卡片内被裁切
 * 且**不出省略号**（省略号是 paragraph 级特性，只由 maxLines 触发，高度约束不参与）。
 */
private val CardHeight = 240.dp

/**
 * 队列里的一张卡片。六张等大、紧密叠放，只有最前一张完整可见。
 *
 * @param entry 该槽位当前的条目；为 null 时渲染占位（保持面板尺寸非零，
 *   否则 WRAP_CONTENT 会测得 0×0，`initial` 可能取不到实体）
 * @param alpha 按槽位静态取的不透明度，用 [androidx.compose.ui.graphics.graphicsLayer] 应用。
 *   过渡的 0.6 秒内不插值（跳变），这是刻意取舍——走插值就要让 Compose 每帧重组。
 * @param nudgeDp **不是样式参数，删掉会让整个队列停止刷新。**
 *   PICO SDK 只在面板**测量尺寸变化**时，才把 ComposeView 重绘后的像素推送到空间表面；
 *   内容变了而尺寸不变时，表面保持陈旧。等大卡片的尺寸恒定，于是每一张都会冻在
 *   冷启动那批内容上。这里用一个随推进轮次翻转的 1dp 宽度抖动强制触发推送。
 *   实测依据见 `.superpowers/.../progress.md` 的 Task 8 判别实验：
 *   让某个槽位的宽度随内容变化后，该槽位立刻恢复更新，相邻的固定宽度槽位依旧冻结。
 */
@Composable
fun HistoryCard(entry: HistoryEntry?, alpha: Float, nudgeDp: Int, onClick: () -> Unit) {
    PicoTheme {
        Column(
            modifier =
                Modifier.graphicsLayer { this.alpha = alpha }
                    .size(CardWidth + nudgeDp.dp, CardHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(true, Material.Thick)
                    .clickable { onClick() }
                    .padding(horizontal = CardPaddingHorizontal, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entry == null) return@Column

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(colorOf(entry.kind))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(labelOf(entry.kind)),
                    style = PicoTheme.typography.labelLarge,
                    color = colorOf(entry.kind),
                )
            }

            Text(
                text = formatYear(entry.year),
                style = PicoTheme.typography.displayMedium,
                color = PicoTheme.colorScheme.labelPrimary,
            )

            Text(
                text = entry.text,
                style = PicoTheme.typography.bodyLargeMultiline,
                color = PicoTheme.colorScheme.labelSecondary,
                maxLines = MaxBodyLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 三种条目类型对应的标签字符串资源。 */
fun labelOf(kind: Kind): Int =
    when (kind) {
        Kind.EVENT -> R.string.kind_event
        Kind.BIRTH -> R.string.kind_birth
        Kind.DEATH -> R.string.kind_death
    }
