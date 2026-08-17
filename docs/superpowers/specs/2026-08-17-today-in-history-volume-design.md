# 历史上的今天 · Volume 应用设计

日期：2026-08-17
状态：已通过设计评审，待实现计划

## 1. 产品定位

一个运行在 PICO OS 6 Volumetric WindowContainer 里的**环境摆件**，不是查资料的工具。

用户偶尔瞥一眼，它自己在慢慢翻。核心画面是一块日期牌，加上一条向纵深延伸的历史条目队列：
最前一条展开正文，后面五条只留年份和单行文本，越远越淡。每 5 分钟自动前进一条，
也可以点击手动前进。

这个定位决定了后面所有取舍：**冷启动即可见 > 数据新鲜度**，**专注单条 > 信息密度**，
**零配置零导航 > 功能完备**。

## 2. 数据源

上游：[LuoH-AN/TodayInHistory](https://github.com/LuoH-AN/TodayInHistory)，`Wiki/WikiJson/{月}-{日}.json`。

采用 Wikipedia 分支而非 Baidu 分支。Wiki 是纯文本、结构统一、每天条目量足（平均 148 条）；
Baidu 虽有摘要和封面图，但每天仅约 12 条，且 `title`/`desc` 含 HTML 标签、封面图是远程 URL，
与「全量离线打包」的决策冲突。

上游数据是 2024-09-21 的静态快照，此后未更新。因此不做运行时网络刷新（YAGNI）。

### 2.1 全量实测

| 指标 | 值 |
|---|---|
| 天数 | 366（含 2/29，无需闰年回落） |
| 条目总数 | 54,193 |
| 平均 / 天 | 148 |
| 最多 / 天 | 310（1-1） |
| 最少 / 天 | 67 |
| 类型分布 | birth 29,441 (54%) · event 14,214 (26%) · death 10,538 (19%) |
| 正文长度 | 中位 18(birth) / 32(event) / 25(death)；p95 48；p99 75；最长 402 |
| 预处理后紧凑 JSON | 4.33 MB |
| 单日文件最大 | 24.5 KB |
| **APK 增量（deflate 后）** | **约 1.6 MB** |

### 2.2 上游数据的三个坑

1. **原始文件按类型分段排列**（event → birth → death），不是按年份排序的。
   跨类型混排必须重新按年份排序。
2. **191 条 `year` 字段不是纯数字**，共 25 种形态：`生不详`（166 条）、`1841（儒略历）`、
   `-1046（周武王十一，亦说-1027）`、`1687，大清康熙二十二八月十三`，
   以及少量抓取噪声（把「维基文库中的相关原始文献」当成了年份）。
3. **公元前年份表示为负数**，排序无碍，显示需转成「公元前 N 年」。

## 3. 架构决策

### 3.1 数据打包：全量进 APK

构建期把预处理后的 JSON 放进 `app/src/main/assets/`，运行时零网络、零权限。

否决的替代方案：
- **运行时拉 GitHub raw** —— 需要网络权限、加载态、失败态、缓存，且 `raw.githubusercontent.com`
  在国内网络可能不可达。对一个「打开就看今天」的摆件，这些代价换不来任何收益。
- **打包 + 后台刷新** —— 上游 2024 年后已停更，这层刷新收益为零。

### 3.2 空间形态：纵深队列

Volume 的深度轴承载时间轴。焦点在 +Z（近观者），队列向 −Z 退去。

否决的替代方案：
- **台历摆件**（3D 台历 + 侧后方长列表）—— 最保险，但换成普通平面窗口也照样成立，
  等于没用上 Volume。
- **环形轨道**（中心 3D 元素自转 + 卡片环绕）—— 视觉好看，但环上一次只放得下 8-12 条，
  144 条数据大部分被浪费。

### 3.3 队列内容：单队列混排

144 条按年份升序串成一条队列，类型只用色点 + 文字标签区分，不做分类切换、不做分栏。

原本担心 54% 的出生条目会让队列失衡，实测推翻了这个担心：**按年份排序后，
同类型连续段中位数为 1，均值 2.0，仅 7.9% 的连续段 ≥5 条** —— 排序本身就把三类打散了。
做类型配额等于无缘无故丢弃好内容。

排序还带来一个额外收益，同一事件的不同视角会自然相邻：

```
1962  逝世   彼得·费希特尔，德国瓦工，于试图逃离东柏林时被守卫射杀（1944年出生）
1962  大事记 东德边防军守卫击毙试图越过柏林围墙进入西柏林的彼得·费查。
```

剩下的是排版问题：出生条目中位 18 字。**焦点卡必须把「一行字」当作常态设计，
而非内容不足的退化情况。**

### 3.4 队列顺序：保序 + 随机起点

按年份升序排列，每次冷启动从随机位置 `Random.nextInt(size)` 切入，循环一圈回到原点。

否决的替代方案：**每次启动 shuffle 全部条目**。它同样解决了「每次打开都是第一条」，
但代价是相邻条目失去关联、深度轴不再具有时间语义、3.3 节那个「同一事件双视角相邻」
的收益也随之消失。保序 + 随机起点用零额外成本拿到了同样的新鲜感。

### 3.5 状态与推进：ECS System 拥有

队列游标、5 分钟计时、槽位推进全部由 `HistoryQueueSystem : System()` 拥有，
在 `update(context)` 中累加 `context.deltaTime`。Compose 只负责面板内部长什么样。

否决的替代方案：`remember` + `LaunchedEffect(delay)` 管队列，`SpatialView.update` 同步 transform。
代码短得多，但每次推进重组整棵 Compose 树，并把 3D transform 的驱动权交给 2D 重组链路。
项目 `AGENTS.md` 明确禁止（"Prefer ECS for 3D runtime behavior"、避免 2D→3D 反馈路径）。
`context.deltaTime` 累加正是 SDK 文档给周期性逻辑的标准写法（见 `TrafficLightSystem` 示例）。

## 4. 运行时结构

```
Volume 容器（AndroidManifest 已配置，本次不改）
└─ SpatialView(fillMaxSize)
   ├─ dateEntity          日期牌，钉在最前上方，不参与队列
   └─ cardEntity[0..5]    1 张焦点 + 5 张待读，冷启动创建 6 个，之后永不增删

   每个 cardEntity 挂两个 component：
     · AttachmentPanelComponent   渲染 SpatialUI 面板（长什么样）
     · HistoryCardComponent       自定义状态：当前条目索引、当前槽位号
```

**6 个 entity 循环复用**。单日条目 67-310 条，但同时可见永远只有 6 张。
推进时只改 transform 和 content，零分配。

### 4.1 推进逻辑

```
advance(step = 1):
    cursor = (cursor + step) mod size
    每个 cardEntity 的 slot -= step
    slot < 0 的 entity 绕回队尾（slot += 6），换成 cursor + 5 对应的条目
    换内容 = attachmentPanelComponent.content { ... }   // 必须重设 content，不会自动刷新
    resetTimer()
```

### 4.2 动画

用 SDK 的补间动画：`TweenAnimation.createTweenAnimation()` +
`AnimationBindTarget.bindPosition()` + `AnimationResource.generateWithTweenAnimation()` +
`entity.playAnimation()`。

槽位是固定的 6 个位置，因此**预生成 6 个 `AnimationResource`**（"移动到槽位 k"），
常驻复用，每次推进只调 `playAnimation`，不再分配。绕回队尾那张不做补间：
先瞬移到 slot5 后方，再淡入。

动画资源必须手动 `close()`（SDK 文档明确要求），在 `DisposableEffect.onDispose` 中释放。

### 4.3 生命周期

- `HistoryQueueSystem` 在 `DisposableEffect(Unit)` 中 `registerSystem` / `unregisterSystem`
- 容器不可见或 App 暂停时挂起计时，恢复后续上
- 所有 `AnimationResource`、`AssetBundle`（若有）在 dispose 时关闭

## 5. 数据层

### 5.1 构建期

`tools/build_history_assets.py` —— 一次性脚本，**产物入库，构建时不联网**。

1. 拉取上游 tarball
2. 解析 `year`：取字符串中第一个整数；落在 `-4000..2100` 之外或含「不详」判为无效
3. 丢弃无效年份条目（实测 172 条 / 0.32%）
4. 跨类型按年份升序**稳定排序**（同年份保持原文件顺序）
5. 压成紧凑数组 `["1945", "…", "e"]`，输出 `app/src/main/assets/history/{M}-{D}.json` × 366

注意 191 与 172 的差别：§2.2 的 191 是**非纯数字 `year` 字符串的出现次数**，
其中 `1841（儒略历）`、`1687，大清康熙…`、`-1046（周武王十一…）` 等能被上述规则正确解析；
真正被判为无效并丢弃的是 172 条（`生不详` 166 条 + 抓取噪声 6 条）。

丢弃而非保留无效年份的理由：焦点卡的主视觉就是年份大字，
一条没有年份的「历史上的今天」在这个 UI 里没有位置。

### 5.2 运行期

```kotlin
data class HistoryEntry(val year: Int, val text: String, val kind: Kind)
enum class Kind { EVENT, BIRTH, DEATH }

object HistoryRepository {
    suspend fun loadDay(month: Int, day: Int): List<HistoryEntry>   // IO 线程，读单个 asset
}
```

## 6. 空间布局

坐标系（已核实）：`SpatialView` 局部空间原点在包围盒几何中心，米制，右手系，**+Z 朝向观者**。

槽位**不写死米数**：`onSizeChanged` 拿 px → `LocalPhysicalLengthConverter` 换算成米，
槽位表达为包围盒尺寸的比例（D = 深度，H = 高度）。

| 槽位 | z | y | scale | 不透明度 | 内容 |
|---|---|---|---|---|---|
| 日期牌 | +0.45D | +0.34H | 1.00 | 1.00 | `8月17日` + 星期 |
| **0 焦点** | +0.30D | −0.12H | 1.00 | 1.00 | 年份大字 + 类型标签 + 正文 |
| 1 | +0.10D | +0.02H | 0.72 | 0.85 | 年份 + 单行文本 |
| 2 | −0.06D | +0.11H | 0.62 | 0.65 | |
| 3 | −0.20D | +0.18H | 0.54 | 0.48 | |
| 4 | −0.32D | +0.24H | 0.47 | 0.34 | |
| 5 | −0.42D | +0.29H | 0.42 | 0.22 | |

**这组数值是起点，不是结论。** Manifest 中 `worldscaletype=2`（Fixed），
远处内容本已因透视自然缩小，上表的 scale 递减叠加在其之上，实际值必须在模拟器上调。

### 6.1 焦点卡排版

正文长度跨度 18-402 字（p95 48，p99 75）。卡片固定宽度，高度自适应，
上限约 10 行；超出部分内部滚动（覆盖 99.9% 以上的条目无需滚动）。

短内容是常态而非异常：18 字的条目应通过年份大字、类型标签、留白构成完整的视觉，
不应显得空。

### 6.2 视觉与主题

全部 2D UI 使用 SpatialUI（`com.pico.spatial.ui.*`）并包裹在 `PicoTheme` 中。
**项目内不得出现 Material / Material3**（`androidx.compose.material`、`material3`、`MaterialTheme`）。

## 7. 交互

| 操作 | 行为 |
|---|---|
| 点焦点卡 | 前进 1 条，计时器重置 |
| 点第 *i* 条待读 | 直接跳 *i* 条，计时器重置 |
| 无操作 | 每 300 秒自动前进 1 条 |

面板使用 Compose `Modifier.clickable`（SDK 示例中确认可用）+ SpatialUI `spatialHoverEffect`
提供悬停反馈。

**未验证项**：AttachmentPanel 在 Volumetric 容器中的点击命中区域是否与视觉边界一致。
代码上无法确认，必须在模拟器验证。若不一致，退路是给卡片实体添加 `InteractableComponent`
\+ `CollisionComponent`，改走 `detectTapGestures(context, targetedToEntity = TargetEntity.hit(entity))`。

## 8. 容器配置

`AndroidManifest.xml` 中的 Volumetric WindowContainer 配置**保持不变**：
`style=2`（Volumetric）、`defaultsize=1000x1000x1000 dp`、`worldscaletype=2`（Fixed）、
`volumealignment=0`（Gravity）、`volumebasepanel=0`（底座可见）、`materialbackground=0`。

底座保持可见，为这组浮牌提供落地感。

**v1 不产出任何 3D 模型内容**。`HomeVolume` 不再加载模板自带的 `MyScene`。
`editor-asset` 模块保留（体积可忽略，将来添加 3D 元素时现成）。
因此本次不触发 `spatial-editor` 工作流，也不受当前 `editor.doctor` 报错影响。

## 9. 错误处理

| 情况 | 行为 |
|---|---|
| asset 文件缺失或解析失败 | 返回空列表，显示「今天没有找到记录」占位卡，不崩溃 |
| 单日条目数 < 6 | 槽位不足时只填充可用条目，空槽位隐藏（实测最少 67 条，此路径为防御性） |
| 单日条目数 == 0 | 同上，仅显示日期牌 + 占位卡 |
| 正文超过 10 行 | 卡片内部滚动 |
| System 重复注册 | `DisposableEffect(Unit)` 保证配对，注册前做幂等检查 |
| 跨午夜 | System 每次 tick 比对当前日期；日期变化时重新加载数据、重新随机起点、重建队列 |
| 动画资源泄漏 | 所有 `AnimationResource` 在 `onDispose` 中 `close()` |

跨午夜这条对环境摆件是真实路径 —— 设备整夜开着，第二天早上应该显示新的一天。

## 10. 验证

### 10.1 JVM 单元测试（`app/src/test`，无需设备）

- 年份解析器：25 种畸形输入 + 负数年份 + 边界值
- 排序稳定性：同年份保持原顺序
- 循环游标：环绕、跳 *i* 条、`step > size` 的取模
- `HistoryRepository`：正常 JSON、空文件、损坏 JSON、缺失文件
- 跨午夜：注入可控时钟，验证日期变化触发重载

### 10.2 设备/模拟器验证（必须实机跑，不可仅凭代码断言）

- 构建、安装、启动，截图确认队列渲染
- 点击焦点卡与各待读条，确认命中区域与视觉边界一致（第 7 节的未验证项）
- 自动推进：临时将间隔调至数秒验证，确认后改回 300 秒
- 槽位数值调优：确认远处条目仍可读、近处不出容器边界
- logcat 无崩溃、无资源泄漏警告
- 确认项目中无 Material / Material3 依赖

## 11. 文件清单

```
tools/build_history_assets.py                          新增  构建期数据脚本
app/src/main/assets/history/{M}-{D}.json               新增  366 个数据文件
app/src/main/java/tech/illusion/todayinhistory/
  data/HistoryEntry.kt                                 新增  数据模型
  data/HistoryRepository.kt                            新增  asset 读取
  content/HomeVolume.kt                                重写  SpatialView + attachments
  content/queue/HistoryCardComponent.kt                新增  ECS 状态组件
  content/queue/HistoryQueueSystem.kt                  新增  ECS 推进系统
  content/queue/SlotLayout.kt                          新增  槽位坐标计算
  content/panels/DatePanel.kt                          新增  日期牌
  content/panels/FocusCard.kt                          新增  焦点卡
  content/panels/WaitingStrip.kt                       新增  待读条
app/src/test/java/tech/illusion/todayinhistory/…       新增  单元测试
```

`Main.kt`、`SpatialApplication.kt`、`LaunchActivity.kt`、`AndroidManifest.xml`、
`build.gradle.kts` 均不改动。

## 12. 遗留与后续

- 第 7 节的点击命中区域为未验证项，实现阶段第一件事就是在模拟器上确认
- 第 6 节槽位数值需实机调优
- 环境中 `editor.doctor`、`project.context.doctor` 报错未修（用户未授权），本次不阻塞：
  ```
  pico-cli project context doctor --project . --plugin pico-spatial-agentic-tools --fix --tool claude-code
  pico-cli editor install -y
  ```
- 未来若添加 3D 元素，须按 `AGENTS.md` 走 `spatial-editor` 工作流
