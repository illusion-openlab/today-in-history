package tech.illusion.todayinhistory.content

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import java.time.LocalDate
import kotlin.random.Random
import kotlinx.coroutines.launch
import tech.illusion.todayinhistory.data.HistoryRepository
import tech.illusion.todayinhistory.data.asAssetSource
import tech.illusion.todayinhistory.panels.DatePanel
import tech.illusion.todayinhistory.panels.FocusCard
import tech.illusion.todayinhistory.panels.WaitingStrip
import tech.illusion.todayinhistory.queue.DatePanelComponent
import tech.illusion.todayinhistory.queue.HistoryCardComponent
import tech.illusion.todayinhistory.queue.HistoryQueueSystem
import tech.illusion.todayinhistory.queue.QueueDriver
import tech.illusion.todayinhistory.queue.QueueHost
import tech.illusion.todayinhistory.queue.SlotLayout
import tech.illusion.todayinhistory.queue.VISIBLE_SLOTS

private const val LOG_TAG = "HomeVolume"

private const val DATE_ATTACHMENT = "date_panel"

private fun cardAttachmentId(slot: Int): String = "card_$slot"

/**
 * 把前七个任务接起来：数据 → [QueueDriver] → [HistoryQueueSystem] → 面板。
 *
 * ### 三条边界，谁也不越界
 *
 * - **时间与队列逻辑**全在 [QueueDriver]（Task 5），本文件一行都不重复；
 * - **3D 姿态**全由 [HistoryQueueSystem] 每帧写进 `TransformComponent`（Task 6），
 *   本文件**不设任何位置**——包括日期牌，见下；
 * - **2D 内容**由三个面板 Composable 渲染（Task 7），只读 [QueueHost.snapshots]。
 *
 * ### 为什么 `initial` 里一个位置都不设
 *
 * `initial` 执行时 [QueueHost.boundsMeters] 还是占位的 `(1,1,1)`（`onSizeChanged` 尚未触发），
 * 按它算出来的坐标是错的；而 `SlotLayout` 的姿态全是**相对包围盒的比例因子**，
 * 容器被缩放时还必须跟随重算。所以位置的唯一权威是 System，`initial` 只负责
 * 「挂组件 + 加进场景」两件事。日期牌因此也必须挂 [DatePanelComponent]
 * （它是纯标记组件，存在的唯一理由就是让日期牌落进 System 的查询）。
 *
 * ### 初次装载由谁发起
 *
 * **不是本文件**。首次 `reset` 之前快照是哨兵 `0/0`，`QueueDriver.dayChanged()` 恒为 true，
 * System 在容器建立后的第一帧就会调 [QueueHost.onDayChanged]。本文件只负责
 * 把回调实现好。若这里再自行发起一次装载，两条路径会各自 `submitReload`，
 * System 先后落地两次 `reset`——第二次重新随机洗牌，把已经渲染出来的内容换掉。
 */
@Composable
fun HomeVolume() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val converter = LocalPhysicalLengthConverter.current
    val scope = rememberCoroutineScope()

    val repository = remember { HistoryRepository(context.assets.asAssetSource()) }
    val driver = remember { QueueDriver(clock = { LocalDate.now() }, random = Random.Default) }

    val snapshot by QueueHost.snapshots.collectAsState()

    DisposableEffect(Unit) {
        // 顺序有意义：driver 和回调都就位之后才注册 System，
        // 否则 System 的第一帧会因为 driver == null 空转一帧。
        QueueHost.driver = driver

        // 在**场景线程**上被调用，必须立刻返回：起个协程就走，绝不在这里做 IO、
        // 也绝不碰 driver（QueueDriver 非线程安全，ECS 对象是 @MainThread）。
        //
        // 无论成功失败都必须回信号。不回的话 System 只能等 30 秒的失联兜底，
        // 那段时间里界面什么都不显示也不重试。
        QueueHost.onDayChanged = {
            scope.launch {
                try {
                    val today = LocalDate.now()
                    // 必须是**今天**的月日：装非今日数据会让 dayChanged() 恒为 true，
                    // 变成每秒重载一次的静默自毁。
                    val entries = repository.loadDay(today.monthValue, today.dayOfMonth)
                    Log.i(LOG_TAG, "装载 ${today.monthValue}-${today.dayOfMonth}：${entries.size} 条")
                    // loadDay 返回的 ArrayList 交出去之后不再触碰（HistoryQueue 不做防御性拷贝）。
                    QueueHost.submitReload(entries, today.monthValue, today.dayOfMonth)
                } catch (t: Throwable) {
                    // 含 CancellationException：组合被销毁时协程会被取消，
                    // 不回信号的话 in-flight 标志会一直挂着。
                    QueueHost.reloadFailed()
                    throw t
                }
            }
        }

        registerSystem<HistoryQueueSystem>()

        onDispose {
            unregisterSystem<HistoryQueueSystem>()
            QueueHost.clear()
        }
    }

    SpatialView(
        modifier =
            Modifier.fillMaxSize().onSizeChanged { size ->
                // px → 米。宽高来自这个回调，**深度没有对应 API**，按约定取较短边
                // （容器在 Manifest 里声明为 1000×1000×1000 dp 的立方体）。
                // 这是代理值不是实测值，深度间距观感不对时第一个该怀疑它。
                with(density) {
                    val widthMeters = converter.dpToLength(size.width.toDp(), LengthUnit.Meters)
                    val heightMeters = converter.dpToLength(size.height.toDp(), LengthUnit.Meters)
                    val depthMeters = minOf(widthMeters, heightMeters)
                    QueueHost.boundsMeters = Vector3(widthMeters, heightMeters, depthMeters)
                    Log.i(
                        LOG_TAG,
                        "包围盒 ${size.width}x${size.height}px -> " +
                            "${widthMeters}x${heightMeters}x${depthMeters}m",
                    )
                }
            },
        initial = { content, attachments ->
            // 日期牌：只挂标记组件，位置归 System。
            val dateEntity = attachments.entity(DATE_ATTACHMENT)
            dateEntity?.apply {
                components.set(DatePanelComponent())
                content.addEntity(this)
            }

            // 6 张卡片：槽位号挂上去之后终生不变——推进换的是槽位里的内容，
            // 不是实体与槽位的绑定。
            var attachedCards = 0
            for (slot in 0 until VISIBLE_SLOTS) {
                attachments.entity(cardAttachmentId(slot))?.apply {
                    components.set(HistoryCardComponent().also { it.slot = slot })
                    content.addEntity(this)
                    attachedCards++
                }
            }
            // 接线证据：这两个数字若不是 1 和 6，后面「面板全堆在原点」就有了确定的解释。
            Log.i(LOG_TAG, "attachment 实体：日期牌=${dateEntity != null}，卡片=$attachedCards/$VISIBLE_SLOTS")
        },
        attachments = {
            AttachmentPanel(id = DATE_ATTACHMENT) {
                // 首次 reset 落地之前快照是哨兵 0/0。此时**不能**渲染 `0月0日`，
                // 但也**不能**什么都不渲染——空内容的 AttachmentPanel 尺寸为 0，
                // `initial` 里可能就取不到这个实体，日期牌会永久缺席。
                //
                // 所以用今天的日期兜底。它不是「看起来合理的假日期」：首次 reset 落地后
                // 快照里装的就是同一个值，兜底期只有冷启动的头一两帧。
                val fallback = remember { LocalDate.now() }
                val hasData = snapshot.month != 0
                DatePanel(
                    month = if (hasData) snapshot.month else fallback.monthValue,
                    day = if (hasData) snapshot.day else fallback.dayOfMonth,
                )
            }

            for (slot in 0 until VISIBLE_SLOTS) {
                AttachmentPanel(id = cardAttachmentId(slot)) {
                    val entry = snapshot.slots.getOrNull(slot)?.entry
                    // alpha 按槽位静态取一次：槽位号终生不变，所以这个值也不会变，
                    // 不会因为队列推进而触发重组。
                    val alpha = SlotLayout.poseFor(slot).alpha
                    if (slot == 0) {
                        FocusCard(entry = entry, alpha = alpha) { QueueHost.onCardClicked(0) }
                    } else {
                        WaitingStrip(entry = entry, alpha = alpha) { QueueHost.onCardClicked(slot) }
                    }
                }
            }
        },
    )
}
