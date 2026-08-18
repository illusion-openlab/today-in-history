package tech.illusion.todayinhistory.queue

import com.pico.spatial.core.ecs.Component

/**
 * 挂在每张历史卡片的 Entity 上，记录它占哪个槽位。
 *
 * [slot] 的语义与 `QueueDriver.poseOf` 的参数、`QueueSnapshot.slots` 的下标**三者同序**，
 * 都是「推进之后」的槽位号。挂载方（Task 7/8）给第 i 个面板实体挂 `slot = i` 即可，
 * 之后**不需要**在每次推进时改写它——推进换的是槽位里的**内容**，不是实体与槽位的绑定。
 *
 * 自定义 Component 必须提供无参默认构造（`Component` 的 KDoc：
 * "When extending this class to define a custom component, you must provide a default
 * constructor."，见 `com.pico.spatial.core.ecs.md:1100`）。
 */
class HistoryCardComponent : Component() {

    var slot: Int = 0

    override fun clone(): Component = HistoryCardComponent().also { it.slot = slot }
}

/**
 * 挂在日期牌 Entity 上的**纯标记组件**（无字段）。
 *
 * 日期牌不参与队列推进，姿态恒为 `SlotLayout.datePose`，但它**仍然必须由
 * [HistoryQueueSystem] 驱动**，不能在 `SpatialView(initial = ...)` 里一次性设好位置：
 *
 * - `initial` 回调执行时 `QueueHost.boundsMeters` 还是默认的 `Vector3(1f, 1f, 1f)`
 *   （`onSizeChanged` 尚未触发），按它算出的位置是错的；
 * - 而 `SlotLayout` 的姿态全是**相对包围盒的比例因子**（见 `SlotPose` KDoc），
 *   容器被用户缩放时必须跟随重算。
 *
 * 没有这个标记的话日期牌就落不进 `applyPoses` 的查询，此后永不修正。
 *
 * 同样必须提供无参默认构造。
 */
class DatePanelComponent : Component() {

    override fun clone(): Component = DatePanelComponent()
}

/**
 * 标记「正在离场的那张卡」。
 *
 * 它不属于六个槽位中的任何一个：队列推进时被推走的那条已经出队，
 * 但视觉上还要向左滑出 [TRANSITION_SECONDS] 秒。姿态由
 * [QueueDriver.exitPoseNow] 给出，不透明度由 `OpacityControllerComponent` 逐帧驱动
 * （**不走 Compose 的 graphicsLayer**——那样要每帧重组，而且尺寸不变时
 * SDK 根本不会把新像素推到空间表面）。
 */
class ExitCardComponent : Component() {
    override fun clone(): Component = ExitCardComponent()
}
