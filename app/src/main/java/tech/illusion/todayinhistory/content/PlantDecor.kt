package tech.illusion.todayinhistory.content

import android.util.Log
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.event.AnimationEvents
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "PlantDecor"

/** assets 里的模型路径。`asset://` 是 `Entity.load` 支持的 scheme 之一。 */
private const val PLANT_ASSET = "asset://plant.usdz"

/**
 * 摆放位置，与 [tech.illusion.todayinhistory.queue.SlotLayout] 同一套约定：
 * 比例因子 × SpatialView 包围盒尺寸（米）。这样改容器尺寸时摆件跟着走，不用重调。
 *
 * 时钟面板在 (0.320, -0.380, 0.450)。这里 z 取更小值把植物放到它**身后**
 * （SpatialView 坐标系 +Z 朝向观者，z 越小越远）。
 */
private const val X_FACTOR = 0.30f
private const val Y_FACTOR = -0.500f
private const val Z_FACTOR = 0.35f

/**
 * 朝向（度）。绕的是**世界轴**，施加顺序为 roll(Z) → pitch(X) → yaw(Y)，
 * 所以三个都非零时不能按「先后独立调」来想，改一个会影响另两个的观感。
 *
 * 摆件通常只需要 [YAW_DEGREES]：让植物原地水平转个方向，换一面朝向观者。
 * 卡片堆用的是同一个量（`SlotLayout` 里的 `StackYaw = 14f`），可以对照着取值。
 */
private const val PITCH_DEGREES = 0f
private const val YAW_DEGREES = 180f
private const val ROLL_DEGREES = 0f

/**
 * 期望的成品高度（米）。模型自身的建模单位不可知，所以不写死 scale，
 * 而是加载后量一次包围盒再反推缩放系数——换模型也不用重新试数。
 */
private const val TARGET_HEIGHT_METERS = 0.2f

/** 量出来的高度若小于这个值，视为包围盒尚未就绪，放弃自动缩放以免除出天文数字。 */
private const val MIN_VALID_HEIGHT = 1e-4f

/** 等待包围盒就绪的轮询间隔与上限。 */
private const val BOUNDS_POLL_MS = 50L
private const val BOUNDS_TIMEOUT_MS = 3000L

/**
 * 把植物摆件加载进场景，并让它的 idle 动画持续循环。
 *
 * 必须在协程里调用：模型 15MB、27 张贴图，同步加载会卡住主线程。
 *
 * @param content `SpatialView(initial = ...)` 给出的场景内容
 * @param boundsProvider 读取 SpatialView 当前包围盒（米）。**传函数而不是值**：
 *   `initial` 可能早于 `onSizeChanged` 执行，那一刻包围盒还是默认的 (1,1,1)，
 *   按它摆放会把植物放到容器外面去。下面会等到它被真正写入再取。
 * @return 加载成功的根实体；失败返回 null（失败只记日志，不抛——摆件缺席不该让整个应用起不来）
 */
suspend fun loadPlantDecor(
    content: SpatialViewContent,
    boundsProvider: () -> Vector3,
): Entity? {
    val plant =
        try {
            withContext(Dispatchers.IO) { Entity.load(PLANT_ASSET) }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "植物模型加载失败：$PLANT_ASSET", t)
            return null
        }

    val bounds = awaitBounds(boundsProvider)

    // 包围盒依赖实体已经进入实体树，顺序不能反。
    content.addEntity(plant)

    val transform = plant.components[TransformComponent::class.java]
    if (transform == null) {
        Log.w(LOG_TAG, "模型根实体没有 TransformComponent，无法摆放")
        return plant
    }

    // 先量后缩：此刻 scale 仍是 1，量到的就是模型的原始尺寸。
    val measured = plant.getVisualBounds(null)?.size
    val scale =
        if (measured != null && measured.y > MIN_VALID_HEIGHT) {
            TARGET_HEIGHT_METERS / measured.y
        } else {
            Log.w(LOG_TAG, "包围盒不可用（measured=$measured），按原始尺寸摆放")
            1f
        }

    transform.position = Vector3(X_FACTOR * bounds.x, Y_FACTOR * bounds.y, Z_FACTOR * bounds.z)
    transform.scaleVector = Vector3(scale, scale, scale)
    transform.eulerAngles =
        EulerAngles(pitch = PITCH_DEGREES, yaw = YAW_DEGREES, roll = ROLL_DEGREES)

    Log.i(
        LOG_TAG,
        "植物就位：原始尺寸=$measured 缩放=$scale 位置=${transform.position} " +
            "朝向=${transform.eulerAngles}",
    )

    playIdleLooping(content, plant)
    return plant
}

/**
 * 等到 `onSizeChanged` 把真实包围盒写进来为止。
 *
 * 判据是「不等于默认的 (1,1,1)」。容器在 Manifest 里声明为 1000dp 立方，实测 0.8m 一边，
 * 不会恰好等于 1m，所以这个判据是可靠的。超时就按当前值摆——摆歪了也比不出现好，
 * 且日志里会留下痕迹。
 */
private suspend fun awaitBounds(boundsProvider: () -> Vector3): Vector3 {
    var waited = 0L
    while (waited < BOUNDS_TIMEOUT_MS) {
        val b = boundsProvider()
        if (b.x != 1f || b.y != 1f || b.z != 1f) return b
        kotlinx.coroutines.delay(BOUNDS_POLL_MS)
        waited += BOUNDS_POLL_MS
    }
    val fallback = boundsProvider()
    Log.w(LOG_TAG, "等包围盒超时（${BOUNDS_TIMEOUT_MS}ms），按 $fallback 摆放")
    return fallback
}

/**
 * 播放模型自带的骨骼动画并保证它一直循环。
 *
 * 循环**不能**在播放时指定：`AnimationPlayConfig` 没有 repeat 字段，
 * 而 `AnimationEvents.Looped` 的文档写的是「If the animation is set to loop upon creation」——
 * 也就是说循环与否是动画资源自身的属性，取决于模型怎么导出的。
 *
 * 所以这里两条路都留：
 * - 资源本身就是循环的 → 只会触发 `Looped`，下面的订阅永远不进；
 * - 资源是一次性的 → 触发 `Completed`，在回调里重播补上循环。
 *
 * 动画由 SDK 的动画系统驱动，不经过 `HistoryQueueSystem.update`
 * （那里实测只有 1~2 Hz，任何逐帧插值都会变成瞬移）。
 */
private fun playIdleLooping(content: SpatialViewContent, plant: Entity) {
    val meshes = plant.findSkinnedMeshEntity().toList()
    if (meshes.isEmpty()) {
        Log.w(LOG_TAG, "模型里没有蒙皮网格，没有骨骼动画可播")
        return
    }

    val resources = meshes.firstOrNull()?.getAnimationResources()
    if (resources.isNullOrEmpty()) {
        Log.w(LOG_TAG, "蒙皮网格上没有动画资源")
        return
    }
    Log.i(LOG_TAG, "找到 ${meshes.size} 个蒙皮网格、${resources.size} 条动画，播放第 0 条")

    // 只播第 0 条：idle 通常是导出时的第一条。模型里若有多条需要挑，改这里的下标。
    val idle = resources[0]
    meshes.forEach { mesh ->
        // 刻意不用 use{}：动画资源必须保持打开才能持续播放，
        // 关掉之后重播会拿到已释放的资源。SDK 示例在此处有同样的注释。
        mesh.playAnimation(idle)
    }

    // 事件日志各自限量：Looped 每循环一次就来一条，不设上限会让一个静置摆件的
    // logcat 无上界增长（和 onSizeChanged 那处是同一类问题）。
    var loopedLogged = 0
    var restartLogged = 0

    content.subscribe(AnimationEvents.Started::class.java) { _ ->
        Log.i(LOG_TAG, "动画 Started")
    }
    content.subscribe(AnimationEvents.Looped::class.java) { _ ->
        if (loopedLogged < 3) {
            loopedLogged++
            Log.i(LOG_TAG, "动画 Looped（资源自带循环，无需手动重播）")
        }
    }
    content.subscribe(AnimationEvents.Completed::class.java) { event ->
        // 走到这里说明资源不是自循环的，手动续上。
        if (restartLogged < 3) {
            restartLogged++
            Log.i(LOG_TAG, "动画 Completed → 手动重播")
        }
        event.playbackController.entity?.playAnimation(idle)
    }
}
