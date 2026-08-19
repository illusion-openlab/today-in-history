package tech.illusion.todayinhistory.content

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.illusion.todayinhistory.data.HistoryRepository
import tech.illusion.todayinhistory.data.asAssetSource
import tech.illusion.todayinhistory.panels.ClockPanel
import tech.illusion.todayinhistory.panels.HistoryCard
import tech.illusion.todayinhistory.queue.DatePanelComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import tech.illusion.todayinhistory.queue.ExitCardComponent
import tech.illusion.todayinhistory.queue.HistoryCardComponent
import tech.illusion.todayinhistory.queue.HistoryQueueSystem
import tech.illusion.todayinhistory.queue.QueueDriver
import tech.illusion.todayinhistory.queue.QueueHost
import tech.illusion.todayinhistory.queue.SlotLayout
import tech.illusion.todayinhistory.queue.VISIBLE_SLOTS

private const val LOG_TAG = "HomeVolume"

private const val EXIT_ATTACHMENT = "exit_card"

private const val CLOCK_ATTACHMENT = "clock_panel"

/**
 * 卡片 attachment 的 id。
 *
 * 提成常量列表而不是每次 `"card_$slot"` 现拼：`AttachmentPanel(id: Any)` 与
 * `SpatialViewAttachments.entity(id: Any)` 的 id 匹配方式（`equals` 还是 `===`）
 * 没有文档说明，而字符串模板每次求值都产生新实例。用同一批实例可以让这个问题
 * 无论 SDK 怎么实现都不发生，成本是一个 6 元素的列表。
 */
private val CARD_ATTACHMENT_IDS = List(VISIBLE_SLOTS) { "card_$it" }

/**
 * 诊断日志开关。
 *
 * **本来该用 `BuildConfig.DEBUG`，但这个项目没有 `BuildConfig`** ——AGP 8 起
 * `buildFeatures.buildConfig` 默认关闭，而 `app/build.gradle.kts` 在本任务里禁改。
 * 退而求其次用一个 `const val`：它是编译期常量，为 `false` 时下面这个 `inline` 函数
 * 连同字符串拼接会被整个折叠掉，与 `BuildConfig.DEBUG` 的运行期成本一致，
 * 差别只是切换要改代码而不是改构建类型。
 *
 * Task 9 若打开 `buildConfig` 构建特性，请把这里换成 `BuildConfig.DEBUG` 并删掉本常量。
 */
private const val VERBOSE_LOG = true

/** 只在 [VERBOSE_LOG] 打开时记一条 INFO。 */
private inline fun debugLog(message: () -> String) {
    if (VERBOSE_LOG) Log.i(LOG_TAG, message())
}

/**
 * 本次 composition 是否仍然是 [QueueHost] 的当前持有者。
 *
 * 释放期的时序是 `unregisterSystem` → `clear()` → 协程被取消 → `catch` 跑。
 * 也就是说回信号发生在 `clear()` **之后**，不判定就会往已经清空的全局单例里
 * 留下一个陈旧的 `Failed`：下一个 HomeVolume 的首帧会消费它，打一条误导性的
 * 「装载失败，将在约 1.0s 后重试」，并把首次装载推迟一个节流窗口。
 */
private fun stillOurs(driver: QueueDriver): Boolean = QueueHost.driver === driver

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

    // SDK 只在面板**测量尺寸变化**时才把重绘后的像素推送到空间表面（Task 8 判别实验实测）。
    // 六张卡等大 ⇒ 尺寸恒定 ⇒ 内容变了表面却不更新。这里让宽度在每次推进时翻转 1dp，
    // 强制触发推送。1dp 肉眼不可见，但**删掉它整个队列会停止刷新**。
    //
    // 为什么用「随快照递增的轮次」而不是从内容派生（例如 year 的奇偶）：
    // 内容派生有约 50% 的推进不会翻转，等于一半时候仍然不更新。
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(snapshot) { revision++ }
    val nudgeDp = revision % 2

    // 时钟：每 10 秒探一次，只有分钟（或日期）变了才写 state，避免无谓重组。
    // clockNudge 取分钟奇偶，保证每分钟翻转一次——否则 `14:32` 与 `14:33` 的测量宽度
    // 完全相同，而 SDK 只在尺寸变化时才把新像素推到空间表面，时间会永远停在启动那一刻。
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            val t = LocalDateTime.now()
            if (t.minute != now.minute || t.dayOfMonth != now.dayOfMonth) now = t
        }
    }
    val clockNudge = now.minute % 2

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
                    debugLog { "装载 ${today.monthValue}-${today.dayOfMonth}：${entries.size} 条" }
                    // loadDay 返回的 ArrayList 交出去之后不再触碰（HistoryQueue 不做防御性拷贝）。
                    if (stillOurs(driver)) {
                        QueueHost.submitReload(entries, today.monthValue, today.dayOfMonth)
                    }
                } catch (cancel: CancellationException) {
                    // 取消必须重抛，否则破坏协程取消语义。
                    // 但信号仍要回：不回的话 in-flight 标志会一直挂着。
                    if (stillOurs(driver)) QueueHost.reloadFailed()
                    throw cancel
                } catch (t: Throwable) {
                    // **绝不重抛。** scope 来自 rememberCoroutineScope()，它的 Job 是普通子 Job
                    // 而非 SupervisorJob：任何非取消异常被重抛都会取消整个作用域，
                    // 此后每一次 scope.launch 都是空转，submitReload / reloadFailed 再也不会
                    // 被调到，System 则永远停在「置 in-flight → 等 30 秒超时 → 重试 → 空转」
                    // 的死循环里，而且 logcat 一行都不会再打。
                    // 今天 loadDay 内部吞掉了所有 Exception，但 OutOfMemoryError 之类的
                    // Error 仍会穿透，且以后往这个 lambda 里加的任何一行代码都可能抛。
                    Log.w(LOG_TAG, "装载失败", t)
                    if (stillOurs(driver)) QueueHost.reloadFailed()
                }
            }
        }

        registerSystem<HistoryQueueSystem>()

        onDispose {
            unregisterSystem<HistoryQueueSystem>()
            // 身份判定：QueueHost 是全局单例，而「新 composition 先建立、旧 composition
            // 后销毁」是 Compose 的常规顺序（容器重建、配置变更、将来的第二个窗口）。
            // 无条件 clear() 会把**新实例**刚注入的 driver / 回调置 null、包围盒退回 (1,1,1)、
            // 快照清成 EMPTY，症状是全部面板冻在原点且 logcat 零输出——因为 driver == null
            // 时 System 在 logQueryCounts 之前就 return 了。
            if (QueueHost.driver === driver) QueueHost.clear()
        }
    }

    SpatialView(
        // `fillMaxSize()` 是**承重的**，不是排版糖：没有它 SpatialView 按内容收缩，
        // `onSizeChanged` 拿到的就不是容器的 2000×2000px 全尺寸，`boundsMeters` 直接错，
        // 而所有姿态都是「比例因子 × boundsMeters」——正是 Task 6 排障表里
        // 「能跑但全错」那一格。与 SDK 官方示例（sample_spatial_view）的用法一致。
        modifier =
            Modifier.fillMaxSize().onSizeChanged { size ->
                // px → 米。宽高来自这个回调，**深度没有对应 API**，按约定取较短边
                // （容器在 Manifest 里声明为 1000×1000×1000 dp 的立方体）。
                // 实测该容器确为 2000×2000×2000px 立方且 resizeRestriction=1（只能等比缩放），
                // 所以在当前 Manifest 下这不是近似而是精确值。改 Manifest 的
                // defaultsize / resizerestriction 时必须回来重看这里。
                with(density) {
                    val widthMeters = converter.dpToLength(size.width.toDp(), LengthUnit.Meters)
                    val heightMeters = converter.dpToLength(size.height.toDp(), LengthUnit.Meters)
                    val depthMeters = minOf(widthMeters, heightMeters)
                    val next = Vector3(widthMeters, heightMeters, depthMeters)
                    // 只在**值真的变了**时记日志。实测这个回调在每次队列推进后会被再打
                    // 两次（面板内容变化 → 重排 → 回调），值恒定不变；无条件打日志会让
                    // 一个静置摆件的 logcat 无上界增长。
                    val changed = QueueHost.boundsMeters.let { it.x != next.x || it.y != next.y || it.z != next.z }
                    QueueHost.boundsMeters = next
                    if (changed) {
                        debugLog { "包围盒 ${size.width}x${size.height}px -> ${next.x}x${next.y}x${next.z}m" }
                    }
                }
            },
        initial = { content, attachments ->
            // 时钟组两层：只挂标记组件，位置归 System。layer 决定它是时间层还是日期层。
            val dateEntity = attachments.entity(CLOCK_ATTACHMENT)
            dateEntity?.apply {
                components.set(DatePanelComponent())
                content.addEntity(this)
            }

            // 6 张卡片：槽位号挂上去之后终生不变——推进换的是槽位里的内容，
            // 不是实体与槽位的绑定。
            var attachedCards = 0
            attachments.entity(EXIT_ATTACHMENT)?.apply {
                components.set(ExitCardComponent())
                components.set(OpacityControllerComponent(0f))
                content.addEntity(this)
            }

            for (slot in 0 until VISIBLE_SLOTS) {
                attachments.entity(CARD_ATTACHMENT_IDS[slot])?.apply {
                    components.set(HistoryCardComponent().also { it.slot = slot })
                    content.addEntity(this)
                    attachedCards++
                }
            }
            // 接线证据：这两个数字若不是 true 和 6，后面「面板全堆在原点」就有了确定的解释。
            debugLog {
                "attachment 实体：日期牌=${dateEntity != null}，卡片=$attachedCards/$VISIBLE_SLOTS"
            }

            // 时钟面板身后的植物摆件。异步加载：模型 15MB，同步会卡住主线程。
            // 传 lambda 而不是当前包围盒——此刻 onSizeChanged 可能还没跑，见 loadPlantDecor 注释。
            scope.launch {
                try {
                    loadPlantDecor(content) { QueueHost.boundsMeters }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    // 与下方装载协程同一个 scope，同样**绝不重抛**：
                    // 重抛会取消这个普通子 Job，之后每一次 scope.launch 都变成空转。
                    Log.w(LOG_TAG, "植物摆件装载异常", t)
                }
            }
        },
        attachments = {
            AttachmentPanel(id = CLOCK_ATTACHMENT) {
                // 首次 reset 落地之前快照是哨兵 0/0，此时用本地时钟兜底。
                // **副作用**：装载链路整体没跑起来时，界面表现为「时钟正常 + 卡片全空」，
                // 不是明显故障。排查时不要从这里入手，去看 logcat 有没有「装载 M-D：N 条」。
                val hasData = snapshot.month != 0
                ClockPanel(
                    hour = now.hour,
                    minute = now.minute,
                    month = if (hasData) snapshot.month else now.monthValue,
                    day = if (hasData) snapshot.day else now.dayOfMonth,
                    weekday = now.dayOfWeek,
                    nudgeDp = clockNudge,
                )
            }

            // 离场卡：承载被推走的那条，向左滑出。它不属于任何槽位，
            // 静止时 OpacityControllerComponent 把它压到全透明，不需要额外显隐开关。
            AttachmentPanel(id = EXIT_ATTACHMENT) {
                HistoryCard(
                    entry = snapshot.exiting,
                    alpha = 1f,
                    nudgeDp = nudgeDp,
                    onClick = {},
                )
            }

            for (slot in 0 until VISIBLE_SLOTS) {
                AttachmentPanel(id = CARD_ATTACHMENT_IDS[slot]) {
                    val entry = snapshot.slots.getOrNull(slot)?.entry
                    // 每次内容 lambda 重新求值时留痕。用来把「Compose 没重组」与
                    // 「重组了但合成器没更新面板」区分开——两者在设备上表现完全相同
                    // （面板显示陈旧内容），只靠截图分不了。
                    debugLog { "面板重组 slot=$slot year=${entry?.year}" }
                    // alpha 按槽位静态取一次：槽位号终生不变，所以这个值也不会变，
                    // 不会因为队列推进而触发重组。
                    val alpha = SlotLayout.poseFor(slot).alpha
                    HistoryCard(entry = entry, alpha = alpha, nudgeDp = nudgeDp) {
                        QueueHost.onCardClicked(slot)
                    }
                }
            }
        },
    )
}
