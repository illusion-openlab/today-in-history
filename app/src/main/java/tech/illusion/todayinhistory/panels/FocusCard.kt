package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
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

/** 类型标签的 string 资源 id，与 `values/strings.xml` 里的 `kind_*` 一一对应。 */
fun labelOf(kind: Kind): Int =
    when (kind) {
        Kind.EVENT -> R.string.kind_event
        Kind.BIRTH -> R.string.kind_birth
        Kind.DEATH -> R.string.kind_death
    }

/**
 * 焦点卡：队列 slot 0，唯一展开正文的一张。
 *
 * ### 排版：一行字是常态，不是退化
 *
 * 实测正文长度中位 18 字（出生条目）/ 32 字（大事记），p95 48 字，p99 75 字，最长 402 字。
 * 也就是说**绝大多数条目只有一两行**。因此这里刻意**不设最小高度**：
 * Column 按内容收缩，18 字的条目得到一张紧凑的卡，而不是一个大半是空白的高盒子。
 * 短内容的「完整感」由类型标签 + 32sp 的年份大字 + 慷慨的内边距共同撑起，
 * 而不是靠把卡片撑大。
 *
 * 超长的极少数（>10 行）用 [heightIn] 封顶后走内部滚动，避免一条 402 字的记录
 * 把卡片拉成一块挡住整个 Volume 的板子。
 *
 * ### alpha 为什么是参数而不是内部计算
 *
 * 「越远越淡」这条视觉规则由面板侧负责：Task 6 的 `HistoryQueueSystem` 有意不写 alpha
 * （实体级不透明度要另一套材质/渲染 API，且会把材质操作引进每帧路径），
 * 见其 KDoc「为什么这里不写 alpha」。调用方（Task 8）传
 * `SlotLayout.poseFor(slot).alpha`。
 *
 * **这是按槽位静态应用，不是每帧插值。** 过渡的 0.6 秒里 alpha 保持各自槽位的值、
 * 到位后跳变，而不是渐变过去。这是刻意取舍：走插值就得让 Compose 在过渡期间每帧重组，
 * 而「不让 2D 重组进入每帧路径」正是本设计要保的东西。焦点卡的 alpha 恒为 1.0
 * （`poses[0].alpha`），所以这个跳变在最显眼的那张卡上根本不发生。
 *
 * @param entry 该槽位的条目；为 null 表示当天没有任何记录（**不表示正文为空**——
 *   Task 1 的构建脚本已在生成资产时丢弃空正文条目，正文保证非空非空白）。
 * @param alpha 槽位不透明度，取自 `SlotLayout.poseFor(slot).alpha`。
 * @param onClick 点击整卡时触发，用于手动推进队列。
 */
@Composable
fun FocusCard(entry: HistoryEntry?, alpha: Float, onClick: () -> Unit) {
    PicoTheme {
        Column(
            modifier =
                // `this.` 不能省：裸写 `alpha = alpha` 时左侧解析到本函数的参数（val），
                // 编译期直接报 "'val' cannot be reassigned"。加 `this.` 后左侧是
                // GraphicsLayerScope.alpha、右侧是参数，即标准的 `this.x = x` 构造器写法。
                Modifier.graphicsLayer { this.alpha = alpha }
                    .width(560.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(true, Material.Thick)
                    .clickable { onClick() }
                    .padding(horizontal = 36.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entry == null) {
                Text(
                    text = stringResource(R.string.empty_day),
                    style = PicoTheme.typography.titleLarge,
                    color = PicoTheme.colorScheme.labelSecondary,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    modifier =
                        Modifier.size(10.dp).clip(CircleShape).background(colorOf(entry.kind))
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
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                text = entry.text,
                // Multiline 变体行高 24sp（bodyLarge 是 20sp），同样 16sp 字号下
                // 多行中文不会挤在一起。正文本来就是段落，用段落的那个 token。
                style = PicoTheme.typography.bodyLargeMultiline,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        }
    }
}
