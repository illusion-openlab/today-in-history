package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.todayinhistory.data.HistoryEntry

/** 待读条宽度。实条与空占位共用，保证面板尺寸不随数据有无而跳变。 */
private val StripWidth = 520.dp

/** 待读条上下内边距。 */
private val StripPaddingVertical = 14.dp

/**
 * 待读条高度 = 上下内边距 + 单行行高，**从当前字号实时推导**。
 *
 * 公式：`14dp × 2 + labelLarge.lineHeight`。`labelLarge` 与 `bodyMedium` 的
 * lineHeight 均为 18sp（`TypeScaleTokens.kt:155-157,185-187`），且基样式带
 * `includeFontPadding = false`，所以单行高度精确等于 lineHeight。
 * fontScale 1.0 下得 14×2 + 18 = **46dp**。
 *
 * ### 为什么必须推导而不能写死 46dp
 *
 * 内边距是 **dp**（不随字号缩放），行高是 **sp**（随字号缩放）——两者量纲不同。
 * 写死 46dp 的话，实条在 fontScale 1.3 下是 28 + 23.4 = 51.4dp，
 * 而占位仍是 46dp，**数据落地时面板高度上跳约 5dp**。
 * 那样「实条与占位同源」就只是名义成立：一个是 sp 派生量、一个是 dp 常量，
 * 只是恰好在默认字号相等。
 *
 * 这里用 `TextUnit.toDp()` 让占位跟着字号一起走，两者在**任意** fontScale 下都相等。
 *
 * 必须在 `PicoTheme { }` 内读取，否则拿到的是 `LocalTypography` 的兜底值。
 */
private val stripMinHeight: Dp
    @Composable
    get() =
        StripPaddingVertical * 2 +
            with(LocalDensity.current) { PicoTheme.typography.labelLarge.lineHeight.toDp() }

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
 * ### entry 为 null 时渲染透明占位，而不是什么都不画
 *
 * `AttachmentPanel(id, size = null)` 走 `WRAP_CONTENT`（`SpatialUIAttachmentPanel.kt:42`），
 * 空 composition 会让 ComposeView 测得 **0×0**，面板实体于是拿到 0×0 尺寸，
 * 而 Task 6 的 `applyPoses` 仍会照常给它写 `TransformComponent`。
 * 「0×0 的 WRAP_CONTENT 面板 SDK 会怎么处理」是个未知数，不该出现在关键路径上。
 *
 * 而这条路径是**必现的**：真实数据每天最少 67 条，`slot >= entries.size` 实际不可达，
 * 但 `QueueSnapshot.EMPTY` 的六个槽位全是 null —— 也就是**每一次冷启动、
 * 首次 `reset` 落地之前，5 个待读条都是空的**。
 *
 * 所以这里不早返回，而是渲染一个与实条**等尺寸的全透明占位**：不画毛玻璃、不画文字、
 * 不可点击，只占住 [StripWidth] × [stripMinHeight]。面板实体因此永远有非零尺寸，
 * 数据落地时尺寸也不会跳变。
 *
 * @param entry 该槽位的条目；为 null 时渲染等尺寸透明占位（见上）。
 * @param alpha 槽位不透明度，取自 `SlotLayout.poseFor(slot).alpha`。
 * @param onClick 点击整条时触发，用于把队列直接推进到这一条。
 */
@Composable
fun WaitingStrip(entry: HistoryEntry?, alpha: Float, onClick: () -> Unit) {
    PicoTheme {
        // 实条与占位共用同一个推导值，两者在任意 fontScale 下都等高。
        val minHeight = stripMinHeight

        if (entry == null) {
            // 等尺寸透明占位：不画毛玻璃、不画文字、不可点击，只占住尺寸。
            Spacer(modifier = Modifier.width(StripWidth).height(minHeight))
            return@PicoTheme
        }

        Row(
            modifier =
                // `this.` 不能省，理由见 FocusCard 同处注释。
                Modifier.graphicsLayer { this.alpha = alpha }
                    .width(StripWidth)
                    // 与占位同源，保证有无数据时面板高度一致、不跳变。
                    .heightIn(min = minHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .backgroundMaterial(true, Material.Regular)
                    .clickable { onClick() }
                    .padding(horizontal = 24.dp, vertical = StripPaddingVertical),
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
