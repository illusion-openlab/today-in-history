# 历史上的今天 · Volume 应用实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已有的 PICO Spatial SDK Volumetric 脚手架上，实现一个离线的「历史上的今天」环境摆件：日期牌 + 一条向纵深延伸的历史条目队列，焦点卡展开正文，5 条待读只留年份与单行文本，每 5 分钟自动前进一条。

**Architecture:** 数据在构建期由 Python 脚本预处理成 366 个单日 JSON 打进 `assets/`，运行时零网络。队列游标、计时、槽位推进与位置插值全部由 ECS `HistoryQueueSystem` 在 `update(context)` 中拥有并逐帧驱动；Compose/SpatialUI 只负责面板内部长什么样，通过 `MutableStateFlow` 单向接收 System 发布的快照。7 个 Entity（1 日期牌 + 6 卡片）冷启动创建后循环复用，永不增删。

**Tech Stack:** Kotlin 2.0.0 / AGP 8.13.2 / PICO Spatial SDK BOM 6.0.0 / Jetpack Compose + SpatialUI / JUnit 4 / Python 3（仅构建期）

**Spec:** `docs/superpowers/specs/2026-08-17-today-in-history-volume-design.md`

## Global Constraints

- 包名 `tech.illusion.todayinhistory`，`applicationId` 同名
- `compileSdk = 35`、`minSdk = 35`、`targetSdk = 35`、`jvmTarget = "11"`、`abiFilters = ["arm64-v8a"]`
- Spatial SDK 版本由 `libs.versions.toml` 的 `spatialBom = "6.0.0"` 决定，**不得升级**
- **禁止 Material / Material3**：项目中不得出现 `androidx.compose.material`、`androidx.compose.material3`、`MaterialTheme`。所有 2D UI 使用 SpatialUI（`com.pico.spatial.ui.*`）并包裹在 `PicoTheme` 中
- `app/build.gradle.kts` 已 `exclude` 了 `androidx.compose.ui:ui`、`ui-graphics`、`ui-text`、`androidx.compose.foundation:foundation`——这些由 Spatial SDK 提供，**不要重新引入**
- **不得修改**：`AndroidManifest.xml`、`Main.kt`、`platform/SpatialApplication.kt`、`platform/LaunchActivity.kt`、**仓库根的 `/build.gradle.kts`**、`settings.gradle.kts`、`gradle/libs.versions.toml`
- `app/build.gradle.kts` **原则上禁改**，唯一例外：把 `src/main/assets` 声明为单元测试任务的输入。
  注意这条例外**只覆盖 `app/build.gradle.kts`**，不覆盖仓库根的 `/build.gradle.kts`（后者始终禁改）。
  该约束的本意是防止靠改依赖/exclude 去凑编译通过；声明任务输入与那个意图正交。
  实测证据：不声明输入时，污染资产后 `:app:testDebugUnitTest` 会 `UP-TO-DATE` 且 `BUILD SUCCESSFUL`，
  真实资产回归测试静默不运行 —— 恰好在它最该起作用的场景（重新生成资产后本地跑测试）失效。
- `System.update(context)` 运行在主线程（`Scene.queryEntity` 与 `ComponentSet` 均标注 `@MainThread`，官方 `FlyTrajectorySystem` 示例在其中直接写 `TransformComponent`）。**不得**在 `update` 中阻塞、启动协程或跨线程调用 ECS API
- 自定义 `Component` 子类必须提供无参默认构造；自定义 `System` 子类必须提供 public 无参构造
- 构建期脚本的产物（366 个 JSON）**入库提交**，构建时不联网

## 与 Spec 的两处偏离（已获批准前请先确认）

1. **§4.2 动画方案**：spec 写的是预生成 6 个 `AnimationResource` 走 `TweenAnimation`。本计划改为**在 `HistoryQueueSystem.update` 中逐帧缓动插值**写 `TransformComponent.position`。理由：System 本来就每帧带 `deltaTime` 被调用，插值只需约 15 行；而 TweenAnimation 路线要引入 6 个资源的创建/复用/`close()` 生命周期。官方 `FlyTrajectorySystem` 正是逐帧写 transform 的写法。功能等价，风险与代码量都更低。
2. **§5.1 年份下界**：spec 写 `-4000`，改为 **`-5000`**。实测 `1-1.json` 有一条合法条目年份为 `-4713`（儒略日纪元起点），`-4000` 会误杀它。改后对全量 54,193 条做回归：纯数字年份 0 错误，丢弃 174 条（0.32%）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `tools/build_history_assets.py` | 构建期：拉取上游 → 解析年份 → 排序 → 输出 366 个单日 JSON |
| `app/src/main/assets/history/{M}-{D}.json` | 产物，366 个文件 |
| `data/HistoryEntry.kt` | 数据模型 `HistoryEntry` / `Kind` |
| `data/AssetSource.kt` | `AssetManager` 的可测试抽象 |
| `data/HistoryRepository.kt` | 读取并解析单日 asset |
| `queue/HistoryQueue.kt` | 纯 Kotlin 队列状态机：游标、环绕、跳转 |
| `queue/SlotLayout.kt` | 纯 Kotlin 槽位姿态表（比例因子） |
| `queue/QueueSnapshot.kt` | System → Compose 的单向快照数据 |
| `queue/HistoryCardComponent.kt` | ECS 状态组件：槽位号 + 插值进度 |
| `queue/HistoryQueueSystem.kt` | ECS 系统：计时、推进、逐帧插值、发布快照 |
| `panels/DatePanel.kt` | 日期牌 Composable |
| `panels/FocusCard.kt` | 焦点卡 Composable |
| `panels/WaitingStrip.kt` | 待读条 Composable |
| `content/HomeVolume.kt` | 组装：SpatialView + attachments + 生命周期 |

Kotlin 源码根：`app/src/main/java/tech/illusion/todayinhistory/`
测试根：`app/src/test/java/tech/illusion/todayinhistory/`

---

## Task 1: 构建期数据脚本

**Files:**
- Create: `tools/build_history_assets.py`
- Create: `app/src/main/assets/history/{M}-{D}.json`（脚本产物，366 个）

**Interfaces:**
- Consumes: 无
- Produces: `app/src/main/assets/history/{M}-{D}.json`，内容为 JSON 数组，每项为 `[年份:Int, 正文:String, 类型码:String]`，类型码 ∈ `"e" | "b" | "d"`，按年份升序稳定排序。

  示例是 `8-17.json` 真实的前两条（不是拼凑的）：
  ```json
  [[333,"石勒，后赵主（274年出生）","d"],[737,"藤原麻吕，日本奈良时代贵族（695年出生）","d"]]
  ```
  注意首条是 `death` 而非 `event`——上游按 event/birth/death 分段排列，本步骤要求跨类型按年份升序，
  两者顺序不同。

- [ ] **Step 1: 写脚本（含自检模式）**

创建 `tools/build_history_assets.py`：

```python
#!/usr/bin/env python3
"""构建 assets/history/ 下的单日 JSON。

用法:
    python3 tools/build_history_assets.py            # 拉取上游并生成
    python3 tools/build_history_assets.py --self-test # 只跑年份解析器自检

产物入库提交；构建时不联网。
"""
import argparse
import io
import json
import os
import re
import sys
import tarfile
import urllib.request

TARBALL = "https://codeload.github.com/LuoH-AN/TodayInHistory/tar.gz/refs/heads/main"
MEMBER_RE = re.compile(r"^[^/]+/Wiki/WikiJson/(\d{1,2})-(\d{1,2})\.json$")
LEAD_YEAR_RE = re.compile(r"^[^\d\-]{0,2}(-?\d+)")
KIND_CODE = {"event": "e", "birth": "b", "death": "d"}

YEAR_MIN = -5000          # 下界取 -5000：1-1 有一条合法条目年份为 -4713（儒略日纪元起点）
YEAR_MAX = 2100
MAX_YEAR_FIELD_LEN = 40   # 超长的 year 字段是上游抓取噪声（把正文段落当成了年份）

OUT_DIR = os.path.join("app", "src", "main", "assets", "history")


def parse_year(raw):
    """把上游的 year 字段解析成整数；无法解析时返回 None。

    规则（已对全量 54,193 条回归验证：纯数字年份 0 错误，丢弃 174 条 / 0.32%）：
      1. 字段长度 > 40 判为噪声
      2. 数字必须出现在开头（允许最多 2 个前导非数字字符，覆盖 "• 2024"）
      3. 结果须落在 [YEAR_MIN, YEAR_MAX]
    """
    if len(raw) > MAX_YEAR_FIELD_LEN:
        return None
    m = LEAD_YEAR_RE.match(raw)
    if not m:
        return None
    value = int(m.group(1))
    if not (YEAR_MIN <= value <= YEAR_MAX):
        return None
    return value


SELF_TEST_CASES = [
    # (输入, 期望)
    ("1945", 1945),
    ("-4713", -4713),
    ("1841（儒略历）", 1841),
    ("-1046（周武王十一，亦说-1027）", -1046),
    ("1687，大清康熙二十二八月十三", 1687),
    ("745（唐玄宗天宝四秋八月壬寅）", 745),
    ("1949:奥萝拉·奎松", 1949),
    ("1958﹕爱荷华州的第一段80号州际公路在得梅因大都会区（英语", 1958),
    ("• 2024", 2024),
    ("1988名", 1988),
    ("生不详", None),
    ("生不祥", None),
    ("维基文库中的相关原始文献", None),
    ("中华民国", None),
    ("大波普（英语", None),
    ("根据《香港法例》第410章，成岁数（有关条文）条例第5条", None),
    ("2月29日相当日之-一日是2月28日时，根据《中华民国民法》第一编总则、第五章「期日及期间」之第121条规定", None),
    ("9999", None),
    ("-99999", None),
]


def self_test():
    failures = []
    for raw, expected in SELF_TEST_CASES:
        actual = parse_year(raw)
        if actual != expected:
            failures.append(f"  parse_year({raw!r}) -> {actual!r}, 期望 {expected!r}")
    if failures:
        print(f"自检失败 {len(failures)}/{len(SELF_TEST_CASES)}:")
        print("\n".join(failures))
        return 1
    print(f"自检通过：{len(SELF_TEST_CASES)}/{len(SELF_TEST_CASES)}")
    return 0


def build():
    print(f"拉取 {TARBALL} …")
    with urllib.request.urlopen(TARBALL) as resp:
        blob = resp.read()

    os.makedirs(OUT_DIR, exist_ok=True)
    days = 0
    kept = 0
    dropped = 0

    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        for member in tar.getmembers():
            m = MEMBER_RE.match(member.name)
            if not m:
                continue
            month, day = int(m.group(1)), int(m.group(2))
            raw_entries = json.load(tar.extractfile(member))

            rows = []
            for item in raw_entries:
                year = parse_year(item["year"])
                if year is None:
                    dropped += 1
                    continue
                code = KIND_CODE.get(item["type"])
                if code is None:
                    dropped += 1
                    continue
                rows.append([year, item["content"], code])

            # 稳定排序：同年份保持上游原始顺序
            rows.sort(key=lambda r: r[0])
            kept += len(rows)
            days += 1

            out_path = os.path.join(OUT_DIR, f"{month}-{day}.json")
            with open(out_path, "w", encoding="utf-8") as fh:
                json.dump(rows, fh, ensure_ascii=False, separators=(",", ":"))

    print(f"生成 {days} 个文件到 {OUT_DIR}")
    print(f"保留 {kept} 条，丢弃 {dropped} 条 ({dropped / (kept + dropped):.2%})")

    if days != 366:
        print(f"错误：期望 366 天，实际 {days}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="只跑年份解析器自检")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else build())
```

- [ ] **Step 2: 跑自检，确认解析器正确**

```bash
python3 tools/build_history_assets.py --self-test
```

期望输出：`自检通过：19/19`

- [ ] **Step 3: 生成数据**

在仓库根目录运行（脚本里的输出路径是相对根目录的）：

```bash
python3 tools/build_history_assets.py
```

期望输出结尾两行（注意顺序：解析统计在前，落盘确认在后 —— 断言通过才落盘）：
```
解析 366 天：保留 54018 条，丢弃 175 条 (0.32%)
生成 366 个文件到 app/src/main/assets/history
```

天数、保留数、丢弃数这**三个**数都是硬断言，不是「接近即可」。175 条丢弃由两个独立过滤器贡献：
`parse_year` 丢掉 174 条无效年份，空正文过滤再丢掉 1 条（`10-15.json` 的 `[1986,"","e"]`，
全库仅此一条，留着会在焦点卡上渲染成一张空白卡片）。

- [ ] **Step 4: 抽检产物**

```bash
ls app/src/main/assets/history | wc -l          # 期望 366

# 注意用 stat 求和而不是 du：366 个小文件在 APFS 上按 4KiB 块分配，
# du 报的是占用块（约 4.9M），不是真实字节。
find app/src/main/assets/history -name '*.json' -exec stat -f%z {} + | paste -sd+ - | bc
# 期望 4,415,254（4.21 MiB）

python3 -c "
import json
d = json.load(open('app/src/main/assets/history/8-17.json'))
print('条目数', len(d))
print('前3条', d[:3])
years = [r[0] for r in d]
assert years == sorted(years), '未按年份升序'
assert all(r[2] in ('e','b','d') for r in d), '类型码非法'
print('校验通过')
"
```

期望：条目数 **143**，`校验通过`，第一条是 `[333, "石勒，后赵主（274年出生）", "d"]`。

注意这两个数不是上游原文的样子：上游 `8-17.json` 原始 144 条，其中 1 条 `year` 为 `生不详`
（嶋野花，日本女性声优）被 `parse_year` 丢弃，故为 143；而 `1307`（铁穆耳加封孔子）只是**上游原始顺序**
的首条——上游按 event/birth/death 分段排列，本步骤要求的是**跨类型按年份升序**，
所以 333/737/1304 三条 death 必然排在它前面。

- [ ] **Step 5: 提交**

```bash
git add tools/build_history_assets.py app/src/main/assets/history
git commit -m "feat(data): 构建期数据脚本与 366 天离线数据"
```

---

## Task 2: 数据模型与 Repository

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/data/HistoryEntry.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/data/AssetSource.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/data/HistoryRepository.kt`
- Test: `app/src/test/java/tech/illusion/todayinhistory/data/HistoryRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `assets/history/{M}-{D}.json` 格式
- Produces:
  - `data class HistoryEntry(val year: Int, val text: String, val kind: Kind)`
  - `enum class Kind { EVENT, BIRTH, DEATH }`，伴生 `fun fromCode(code: String): Kind?`
  - `fun interface AssetSource { fun open(path: String): InputStream }`
  - `class HistoryRepository(private val source: AssetSource)`，方法 `suspend fun loadDay(month: Int, day: Int): List<HistoryEntry>`（失败返回空列表，不抛异常）

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/todayinhistory/data/HistoryRepositoryTest.kt`：

```kotlin
package tech.illusion.todayinhistory.data

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAssets(private val files: Map<String, String>) : AssetSource {
    override fun open(path: String): InputStream {
        val body = files[path] ?: throw FileNotFoundException(path)
        return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}

class HistoryRepositoryTest {

    @Test
    fun `解析正常的单日文件`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(
                mapOf(
                    "history/8-17.json" to
                        """[[1307,"铁穆耳加封孔子","e"],[1893,"华彦钧出生","b"],[1850,"圣马丁逝世","d"]]"""
                )
            )
        )

        val entries = repo.loadDay(8, 17)

        assertEquals(3, entries.size)
        assertEquals(HistoryEntry(1307, "铁穆耳加封孔子", Kind.EVENT), entries[0])
        assertEquals(HistoryEntry(1893, "华彦钧出生", Kind.BIRTH), entries[1])
        assertEquals(HistoryEntry(1850, "圣马丁逝世", Kind.DEATH), entries[2])
    }

    @Test
    fun `支持公元前的负数年份`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/1-1.json" to """[[-4713,"儒略日开始计算","e"]]"""))
        )

        assertEquals(-4713, repo.loadDay(1, 1).single().year)
    }

    @Test
    fun `文件缺失时返回空列表而不抛异常`() = runBlocking {
        val repo = HistoryRepository(FakeAssets(emptyMap()))

        assertTrue(repo.loadDay(2, 30).isEmpty())
    }

    @Test
    fun `JSON 损坏时返回空列表而不抛异常`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/3-1.json" to """[[1900,"缺少收尾"""))
        )

        assertTrue(repo.loadDay(3, 1).isEmpty())
    }

    @Test
    fun `空数组返回空列表`() = runBlocking {
        val repo = HistoryRepository(FakeAssets(mapOf("history/4-1.json" to "[]")))

        assertTrue(repo.loadDay(4, 1).isEmpty())
    }

    @Test
    fun `跳过类型码非法的条目而不整体失败`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(
                mapOf(
                    "history/5-1.json" to
                        """[[1900,"保留","e"],[1901,"丢弃","x"],[1902,"保留","d"]]"""
                )
            )
        )

        val entries = repo.loadDay(5, 1)

        assertEquals(2, entries.size)
        assertEquals(listOf(1900, 1902), entries.map { it.year })
    }

    @Test
    fun `月日不补零地拼接路径`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/1-5.json" to """[[1900,"命中","e"]]"""))
        )

        assertEquals("命中", repo.loadDay(1, 5).single().text)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.data.HistoryRepositoryTest"
```

期望：编译失败，`Unresolved reference: AssetSource` / `HistoryRepository` / `HistoryEntry`。

- [ ] **Step 3: 写实现**

创建 `data/HistoryEntry.kt`：

```kotlin
package tech.illusion.todayinhistory.data

/** 一条「历史上的今天」。[year] 为负数表示公元前。 */
data class HistoryEntry(val year: Int, val text: String, val kind: Kind)

enum class Kind {
    EVENT,
    BIRTH,
    DEATH;

    companion object {
        /** 构建期脚本写入的单字符类型码，非法码返回 null。 */
        fun fromCode(code: String): Kind? =
            when (code) {
                "e" -> EVENT
                "b" -> BIRTH
                "d" -> DEATH
                else -> null
            }
    }
}
```

创建 `data/AssetSource.kt`：

```kotlin
package tech.illusion.todayinhistory.data

import android.content.res.AssetManager
import java.io.InputStream

/**
 * [AssetManager] 的最小抽象，让 [HistoryRepository] 能在 JVM 单元测试里跑，
 * 不需要 Android instrumentation。
 */
fun interface AssetSource {
    /** 打开相对 assets 根的文件；不存在时抛异常。 */
    fun open(path: String): InputStream
}

fun AssetManager.asAssetSource(): AssetSource = AssetSource { path -> open(path) }
```

创建 `data/HistoryRepository.kt`：

```kotlin
package tech.illusion.todayinhistory.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取构建期生成的单日数据。
 *
 * 文件格式见 tools/build_history_assets.py：JSON 数组，每项为 [年份, 正文, 类型码]，
 * 已按年份升序稳定排序。
 *
 * 这里手写解析而不用 org.json：`org.json` 在 JVM 单元测试中是「所有方法抛异常」的桩，
 * 要让它可用就得改 build.gradle.kts 的 testOptions，而 Global Constraints 禁止改构建文件。
 * 数据格式是我们自己在构建期生成的、完全受控，手写解析器足够且零依赖。
 */
class HistoryRepository(private val source: AssetSource) {

    /** 读取某天的全部条目。任何失败都返回空列表——摆件不应该因为数据问题崩溃。 */
    suspend fun loadDay(month: Int, day: Int): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            val path = "history/$month-$day.json"
            val text =
                try {
                    source.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    return@withContext emptyList()
                }

            try {
                parse(text)
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun parse(text: String): List<HistoryEntry> {
        val result = ArrayList<HistoryEntry>()
        var i = 0

        fun skipWhitespace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            skipWhitespace()
            require(i < text.length && text[i] == c) { "位置 $i 期望 '$c'" }
            i++
        }

        fun readInt(): Int {
            skipWhitespace()
            val start = i
            if (i < text.length && text[i] == '-') i++
            while (i < text.length && text[i].isDigit()) i++
            require(i > start) { "位置 $start 期望整数" }
            return text.substring(start, i).toInt()
        }

        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < text.length) { "字符串未闭合" }
                when (val c = text[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        require(i < text.length) { "转义未闭合" }
                        when (val esc = text[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                require(i + 4 < text.length) { "\\u 转义不完整" }
                                sb.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(esc)
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        expect('[')
        skipWhitespace()
        if (i < text.length && text[i] == ']') return result

        while (true) {
            expect('[')
            val year = readInt()
            expect(',')
            val body = readString()
            expect(',')
            val code = readString()
            expect(']')

            Kind.fromCode(code)?.let { result.add(HistoryEntry(year, body, it)) }

            skipWhitespace()
            require(i < text.length) { "数组未闭合" }
            when (text[i]) {
                ',' -> i++
                ']' -> return result
                else -> throw IllegalArgumentException("位置 $i 期望 ',' 或 ']'")
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.data.HistoryRepositoryTest"
```

期望：7 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/data app/src/test/java/tech/illusion/todayinhistory/data
git commit -m "feat(data): HistoryEntry 模型与离线 Repository"
```

---

## Task 3: 队列状态机

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/HistoryQueue.kt`
- Test: `app/src/test/java/tech/illusion/todayinhistory/queue/HistoryQueueTest.kt`

**Interfaces:**
- Consumes: `HistoryEntry`（Task 2）
- Produces:
  - `const val VISIBLE_SLOTS = 6`（1 焦点 + 5 待读）
  - `class HistoryQueue(private val entries: List<HistoryEntry>, startIndex: Int)`
  - `val size: Int`
  - `var cursor: Int`（private set）
  - `fun entryAt(slot: Int): HistoryEntry?` —— slot 0 是焦点，1..5 是待读；条目不足时返回 null
  - `fun advance(step: Int = 1)` —— 游标前进并对 `size` 取模；`size == 0` 时是 no-op
  - `companion object { fun randomStart(entries: List<HistoryEntry>, random: Random): HistoryQueue }`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/todayinhistory/queue/HistoryQueueTest.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

private fun entries(vararg years: Int): List<HistoryEntry> =
    years.map { HistoryEntry(it, "第 $it 年", Kind.EVENT) }

class HistoryQueueTest {

    @Test
    fun `槽位从游标开始依次取条目`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        assertEquals(1, q.entryAt(0)!!.year)
        assertEquals(2, q.entryAt(1)!!.year)
        assertEquals(6, q.entryAt(5)!!.year)
    }

    @Test
    fun `从非零起点开始`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 3)

        assertEquals(4, q.entryAt(0)!!.year)
        assertEquals(5, q.entryAt(1)!!.year)
    }

    @Test
    fun `前进一条后焦点变成原来的第二条`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        q.advance()

        assertEquals(2, q.entryAt(0)!!.year)
        assertEquals(7, q.entryAt(5)!!.year)
    }

    @Test
    fun `前进多条`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        q.advance(step = 3)

        assertEquals(4, q.entryAt(0)!!.year)
    }

    @Test
    fun `槽位越过末尾时环绕回开头`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 6)

        assertEquals(7, q.entryAt(0)!!.year)
        assertEquals(8, q.entryAt(1)!!.year)
        assertEquals(1, q.entryAt(2)!!.year) // 环绕
        assertEquals(2, q.entryAt(3)!!.year)
    }

    @Test
    fun `游标环绕一整圈后回到起点`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance(step = 3)

        assertEquals(0, q.cursor)
        assertEquals(1, q.entryAt(0)!!.year)
    }

    @Test
    fun `步长大于总数时取模`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        q.advance(step = 7) // 7 mod 3 == 1

        assertEquals(2, q.entryAt(0)!!.year)
    }

    @Test
    fun `条目数少于槽位数时多余槽位为空`() {
        val q = HistoryQueue(entries(1, 2, 3), startIndex = 0)

        assertEquals(1, q.entryAt(0)!!.year)
        assertEquals(2, q.entryAt(1)!!.year)
        assertEquals(3, q.entryAt(2)!!.year)
        assertNull(q.entryAt(3))
        assertNull(q.entryAt(5))
    }

    @Test
    fun `空队列所有槽位为空且前进是空操作`() {
        val q = HistoryQueue(emptyList(), startIndex = 0)

        assertEquals(0, q.size)
        assertNull(q.entryAt(0))

        q.advance() // 不应抛异常

        assertEquals(0, q.cursor)
    }

    @Test
    fun `槽位号越界返回空`() {
        val q = HistoryQueue(entries(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)

        assertNull(q.entryAt(-1))
        assertNull(q.entryAt(VISIBLE_SLOTS))
    }

    @Test
    fun `randomStart 覆盖整个下标区间且起点完全由传入的 Random 决定`() {
        val list = entries(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        fun cursorsOfFixedSeeds() =
            (0 until 200).map { seed -> HistoryQueue.randomStart(list, Random(seed)).cursor }

        val first = cursorsOfFixedSeeds()

        // 覆盖集恰好相等：一次排除常量起点、nextInt(size-1)、nextInt(1,size) 三类错误
        assertEquals(list.indices.toSet(), first.toSet())
        // 逐位比较两趟：若实现忽略传入的 random 改用全局源，200 位全撞的概率约 1e-200
        assertEquals("起点必须完全由传入的 Random 决定", first, cursorsOfFixedSeeds())
    }

    @Test
    fun `randomStart 对空列表返回空队列`() {
        val q = HistoryQueue.randomStart(emptyList(), Random(0))

        assertEquals(0, q.size)
        assertEquals(0, q.cursor)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.HistoryQueueTest"
```

期望：编译失败，`Unresolved reference: HistoryQueue`。

- [ ] **Step 3: 写实现**

创建 `queue/HistoryQueue.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import kotlin.random.Random
import tech.illusion.todayinhistory.data.HistoryEntry

/** 同时可见的槽位数：slot 0 是焦点卡，slot 1..5 是待读条。 */
const val VISIBLE_SLOTS = 6

/**
 * 循环阅读队列。
 *
 * [entries] 已按年份升序排好（构建期完成），队列不再重排——从随机起点切入并绕一圈，
 * 既每次打开都不同，又保住了深度轴的时间语义。
 */
class HistoryQueue(private val entries: List<HistoryEntry>, startIndex: Int) {

    val size: Int
        get() = entries.size

    var cursor: Int = if (entries.isEmpty()) 0 else startIndex.mod(entries.size)
        private set

    /** 取第 [slot] 个槽位当前该显示的条目；槽位越界或条目不足时返回 null。 */
    fun entryAt(slot: Int): HistoryEntry? {
        if (slot !in 0 until VISIBLE_SLOTS) return null
        if (slot >= entries.size) return null
        return entries[(cursor + slot).mod(entries.size)]
    }

    /** 游标前进 [step] 条并环绕。空队列时为空操作。 */
    fun advance(step: Int = 1) {
        if (entries.isEmpty()) return
        cursor = (cursor + step).mod(entries.size)
    }

    companion object {
        /** 冷启动时调用一次：从随机位置切入。 */
        fun randomStart(entries: List<HistoryEntry>, random: Random): HistoryQueue =
            HistoryQueue(
                entries = entries,
                startIndex = if (entries.isEmpty()) 0 else random.nextInt(entries.size),
            )
    }
}
```

`Int.mod(Int)` 是 Kotlin 标准库函数，对负数返回非负结果——这正是环绕需要的语义（`(-1).mod(8) == 7`），不要用 `%`。

**但上面这段警告，本任务的 12 条测试一条也验证不到。** 它们的 `startIndex` 只用 0/3/6、`step` 只用 1/3/7，
全是非负数，而 `a % n` 与 `a.mod(n)` 在非负输入上恒等——一个用 `%` 写的错误实现能让这 12 条全绿。

实测变异验证：把实现的三处 `.mod` 换成 `%`，只有下列负数用例会红，原 12 条一条不红。
所以**必须**补上这三类用例：

- 负步长回退并环绕到末尾（`advance(step = -1)` 从 cursor 0 应到 size-1）
- 负步长绝对值大于总数时游标仍非负（如 `advance(step = -13)` 在 size 8 上）
- 负起点被归一化成非负游标（`HistoryQueue(entries, startIndex = -3)`）

另外 brief 原文的 `randomStart 产生的起点落在合法区间内` 用了 Kotlin 的 `assert(...)`，
它受 JVM `-ea` 开关控制，关掉即恒真——改用 `assertTrue`。

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.HistoryQueueTest"
```

期望：全部 PASS。

**注意测试条数**：上面列出的是基础用例，加上本节要求补的三类负数用例（`mod` vs `%`）、
`size < VISIBLE_SLOTS` 且 `cursor != 0` 的防重影用例、以及 `assertEquals(6, VISIBLE_SLOTS)`，
实际条数会多于列出的数量。**不要把「条数对上」当作验收标准**——验收标准是变异验证：
把实现的 `.mod` 换成 `%` 必须有测试变红，把 `randomStart` 换成忽略入参的全局随机源也必须**稳定**变红。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/queue app/src/test/java/tech/illusion/todayinhistory/queue
git commit -m "feat(queue): 循环阅读队列状态机"
```

---

## Task 4: 槽位布局

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/SlotLayout.kt`
- Test: `app/src/test/java/tech/illusion/todayinhistory/queue/SlotLayoutTest.kt`

**Interfaces:**
- Consumes: `VISIBLE_SLOTS`（Task 3）
- Produces:
  - `data class SlotPose(val zFactor: Float, val yFactor: Float, val scale: Float, val alpha: Float)`
  - `object SlotLayout`
    - `val datePose: SlotPose`
    - `fun poseFor(slot: Int): SlotPose` —— slot 越界时钳制到最近的合法槽位
    - `fun lerp(from: SlotPose, to: SlotPose, t: Float): SlotPose` —— `t` 在 0..1 外会被钳制
    - `fun easeOutCubic(t: Float): Float`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/todayinhistory/queue/SlotLayoutTest.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 1e-5f

class SlotLayoutTest {

    @Test
    fun `焦点槽位不透明且原尺寸`() {
        val pose = SlotLayout.poseFor(0)

        assertEquals(1f, pose.scale, EPS)
        assertEquals(1f, pose.alpha, EPS)
    }

    @Test
    fun `槽位越深则 z 越小 尺寸越小 越透明`() {
        for (slot in 1 until VISIBLE_SLOTS) {
            val near = SlotLayout.poseFor(slot - 1)
            val far = SlotLayout.poseFor(slot)

            assertTrue("slot $slot 的 z 应比前一个小", far.zFactor < near.zFactor)
            assertTrue("slot $slot 的 scale 应比前一个小", far.scale < near.scale)
            assertTrue("slot $slot 的 alpha 应比前一个小", far.alpha < near.alpha)
            assertTrue("slot $slot 的 y 应比前一个高", far.yFactor > near.yFactor)
        }
    }

    @Test
    fun `所有槽位的因子都落在容器范围内`() {
        for (slot in 0 until VISIBLE_SLOTS) {
            val pose = SlotLayout.poseFor(slot)

            assertTrue("z 越界: ${pose.zFactor}", pose.zFactor in -0.5f..0.5f)
            assertTrue("y 越界: ${pose.yFactor}", pose.yFactor in -0.5f..0.5f)
            assertTrue("scale 越界: ${pose.scale}", pose.scale in 0f..1f)
            assertTrue("alpha 越界: ${pose.alpha}", pose.alpha in 0f..1f)
        }
    }

    @Test
    fun `日期牌在焦点卡前方且更高`() {
        val date = SlotLayout.datePose
        val focus = SlotLayout.poseFor(0)

        assertTrue(date.zFactor > focus.zFactor)
        assertTrue(date.yFactor > focus.yFactor)
    }

    @Test
    fun `槽位越界被钳制到最近的合法槽位`() {
        assertEquals(SlotLayout.poseFor(0), SlotLayout.poseFor(-3))
        assertEquals(SlotLayout.poseFor(VISIBLE_SLOTS - 1), SlotLayout.poseFor(VISIBLE_SLOTS + 4))
    }

    @Test
    fun `插值端点等于原姿态`() {
        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(a, SlotLayout.lerp(a, b, 0f))
        assertEquals(b, SlotLayout.lerp(a, b, 1f))
    }

    @Test
    fun `插值中点取两端平均`() {
        val a = SlotPose(zFactor = 0f, yFactor = 0f, scale = 0f, alpha = 0f)
        val b = SlotPose(zFactor = 1f, yFactor = 2f, scale = 4f, alpha = 8f)

        val mid = SlotLayout.lerp(a, b, 0.5f)

        assertEquals(0.5f, mid.zFactor, EPS)
        assertEquals(1f, mid.yFactor, EPS)
        assertEquals(2f, mid.scale, EPS)
        assertEquals(4f, mid.alpha, EPS)
    }

    @Test
    fun `插值参数越界被钳制`() {
        val a = SlotLayout.poseFor(0)
        val b = SlotLayout.poseFor(3)

        assertEquals(a, SlotLayout.lerp(a, b, -2f))
        assertEquals(b, SlotLayout.lerp(a, b, 5f))
    }

    @Test
    fun `缓动函数端点为零和一且单调递增`() {
        assertEquals(0f, SlotLayout.easeOutCubic(0f), EPS)
        assertEquals(1f, SlotLayout.easeOutCubic(1f), EPS)

        var prev = -1f
        for (i in 0..20) {
            val v = SlotLayout.easeOutCubic(i / 20f)
            assertTrue("缓动应单调递增", v > prev)
            prev = v
        }
    }

    @Test
    fun `缓动前段快于线性`() {
        assertTrue(SlotLayout.easeOutCubic(0.25f) > 0.25f)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.SlotLayoutTest"
```

期望：编译失败，`Unresolved reference: SlotLayout`。

- [ ] **Step 3: 写实现**

创建 `queue/SlotLayout.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

/**
 * 一个槽位的姿态，全部表达为**相对容器包围盒的比例因子**，不是米。
 *
 * 运行时由 HomeVolume 拿到 SpatialView 的实际尺寸（米）后相乘得到真实坐标，
 * 这样容器被用户缩放时布局自动跟随。
 *
 * @param zFactor 深度方向占比，正值朝向观者（SpatialView 坐标系 +Z 朝观者）
 * @param yFactor 垂直方向占比，正值向上
 */
data class SlotPose(
    val zFactor: Float,
    val yFactor: Float,
    val scale: Float,
    val alpha: Float,
)

object SlotLayout {

    /** 日期牌：钉在最前上方，不参与队列推进。 */
    val datePose = SlotPose(zFactor = 0.45f, yFactor = 0.34f, scale = 1.00f, alpha = 1.00f)

    /**
     * 槽位 0 是焦点卡，1..5 是待读条。
     *
     * 注意：容器 worldscaletype=2（Fixed），远处内容本已因透视自然缩小，
     * 这里的 scale 递减是叠加在其之上的。这组数值是起点，需在设备上调优（Task 9）。
     */
    private val poses =
        listOf(
            SlotPose(zFactor = 0.30f, yFactor = -0.12f, scale = 1.00f, alpha = 1.00f),
            SlotPose(zFactor = 0.10f, yFactor = 0.02f, scale = 0.72f, alpha = 0.85f),
            SlotPose(zFactor = -0.06f, yFactor = 0.11f, scale = 0.62f, alpha = 0.65f),
            SlotPose(zFactor = -0.20f, yFactor = 0.18f, scale = 0.54f, alpha = 0.48f),
            SlotPose(zFactor = -0.32f, yFactor = 0.24f, scale = 0.47f, alpha = 0.34f),
            SlotPose(zFactor = -0.42f, yFactor = 0.29f, scale = 0.42f, alpha = 0.22f),
        )

    init {
        require(poses.size == VISIBLE_SLOTS) { "姿态表必须覆盖全部 $VISIBLE_SLOTS 个槽位" }
    }

    /** 越界的槽位号被钳制到最近的合法槽位。 */
    fun poseFor(slot: Int): SlotPose = poses[slot.coerceIn(0, VISIBLE_SLOTS - 1)]

    /**
     * 端点精确的线性插值。
     *
     * **不要写成 `from + (to - from) * k`** —— 那个形式在 Float 精度下 k=1 时端点不精确：
     * 实测 `0.3f -> -0.2f` 得 `-0.19999999`，`1.0f -> 0.48f` 得 `0.48000002`，
     * 会让 `lerp(a, b, 1f) == b` 的相等断言失败。卡片每 5 分钟推进一次，
     * 端点不精确还会逐轮累积 ulp 偏移，使「当前姿态 == 某槽位姿态」的判断永久失效。
     */
    fun lerp(from: SlotPose, to: SlotPose, t: Float): SlotPose {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        return SlotPose(
            zFactor = from.zFactor * inv + to.zFactor * k,
            yFactor = from.yFactor * inv + to.yFactor * k,
            scale = from.scale * inv + to.scale * k,
            alpha = from.alpha * inv + to.alpha * k,
        )
    }

    /** 三次缓出：起步快、收尾稳，卡片推进时观感最自然。 */
    fun easeOutCubic(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        return 1f - inv * inv * inv
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.SlotLayoutTest"
```

期望：10 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/queue/SlotLayout.kt app/src/test/java/tech/illusion/todayinhistory/queue/SlotLayoutTest.kt
git commit -m "feat(queue): 槽位姿态表与缓动插值"
```

---

## Task 5: 队列驱动器（可测试的核心）

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/QueueSnapshot.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/QueueDriver.kt`
- Test: `app/src/test/java/tech/illusion/todayinhistory/queue/QueueDriverTest.kt`

把「计时 / 推进 / 插值进度 / 跨午夜」这些逻辑从 ECS System 里剥出来放进纯 Kotlin 的 `QueueDriver`，
这样它们全都能在 JVM 上测。Task 6 的 `HistoryQueueSystem` 只剩一层极薄的 ECS 胶水。

**Interfaces:**
- Consumes: `HistoryQueue`、`VISIBLE_SLOTS`、`SlotPose`、`SlotLayout`、`HistoryEntry`
- Produces:
  - `const val AUTO_ADVANCE_SECONDS = 300f`
  - `const val TRANSITION_SECONDS = 0.6f`
  - `data class SlotContent(val slot: Int, val entry: HistoryEntry?)`
  - `data class QueueSnapshot(val slots: List<SlotContent>, val month: Int, val day: Int)`
  - `class QueueDriver(private val clock: () -> LocalDate, private val random: Random)`
    - `var snapshot: QueueSnapshot`（private set）
    - `fun reset(entries: List<HistoryEntry>, month: Int, day: Int)`
    - `fun tick(deltaSeconds: Float)`
    - `fun requestAdvance(step: Int)`
    - `fun poseOf(slot: Int): SlotPose` —— 含推进过程中的插值
    - `fun secondsUntilAutoAdvance(): Float`
    - `val isTransitioning: Boolean`
    - `fun dayChanged(): Boolean`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/tech/illusion/todayinhistory/queue/QueueDriverTest.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

private const val EPS = 1e-4f

private fun entries(vararg years: Int): List<HistoryEntry> =
    years.map { HistoryEntry(it, "第 $it 年", Kind.EVENT) }

private fun driver(
    today: LocalDate = LocalDate.of(2026, 8, 17),
    seed: Int = 0,
): Pair<QueueDriver, MutableList<LocalDate>> {
    val now = mutableListOf(today)
    val d = QueueDriver(clock = { now.first() }, random = Random(seed))
    return d to now
}

class QueueDriverTest {

    @Test
    fun `reset 后快照填满所有槽位`() {
        val (d, _) = driver()

        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), month = 8, day = 17)

        assertEquals(VISIBLE_SLOTS, d.snapshot.slots.size)
        assertEquals(8, d.snapshot.month)
        assertEquals(17, d.snapshot.day)
        assertTrue(d.snapshot.slots.all { it.entry != null })
    }

    @Test
    fun `不到 5 分钟不自动推进`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val before = d.snapshot.slots[0].entry

        d.tick(AUTO_ADVANCE_SECONDS - 1f)

        assertEquals(before, d.snapshot.slots[0].entry)
    }

    @Test
    fun `满 5 分钟自动推进一条`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val first = d.snapshot.slots[0].entry!!.year
        val second = d.snapshot.slots[1].entry!!.year

        d.tick(AUTO_ADVANCE_SECONDS)

        assertNotEquals(first, d.snapshot.slots[0].entry!!.year)
        assertEquals(second, d.snapshot.slots[0].entry!!.year)
    }

    @Test
    fun `手动推进后计时器重置`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(AUTO_ADVANCE_SECONDS - 10f)
        d.requestAdvance(step = 1)
        d.tick(0f)

        assertEquals(AUTO_ADVANCE_SECONDS, d.secondsUntilAutoAdvance(), 0.01f)
    }

    @Test
    fun `手动跳转多条`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val target = d.snapshot.slots[3].entry!!.year

        d.requestAdvance(step = 3)
        d.tick(0f)

        assertEquals(target, d.snapshot.slots[0].entry!!.year)
    }

    @Test
    fun `推进开始时进入过渡状态 结束后退出`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(0f)
        assertTrue(d.isTransitioning)

        d.tick(TRANSITION_SECONDS)
        assertFalse(d.isTransitioning)
    }

    @Test
    fun `过渡中槽位姿态介于相邻两个槽位之间`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS / 2f)

        // slot 1 正在往 slot 0 移动，z 应落在两者之间
        val pose = d.poseOf(1)
        val from = SlotLayout.poseFor(1)
        val to = SlotLayout.poseFor(0)

        assertTrue("z=${pose.zFactor} 应大于 ${from.zFactor}", pose.zFactor > from.zFactor)
        assertTrue("z=${pose.zFactor} 应小于 ${to.zFactor}", pose.zFactor < to.zFactor)
    }

    @Test
    fun `过渡结束后姿态精确落在目标槽位`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.requestAdvance(1)
        d.tick(TRANSITION_SECONDS + 0.1f)

        assertEquals(SlotLayout.poseFor(2).zFactor, d.poseOf(2).zFactor, EPS)
    }

    @Test
    fun `空数据时 tick 不崩溃且槽位全空`() {
        val (d, _) = driver()

        d.reset(emptyList(), 2, 30)
        d.tick(AUTO_ADVANCE_SECONDS * 3)

        assertEquals(VISIBLE_SLOTS, d.snapshot.slots.size)
        assertTrue(d.snapshot.slots.all { it.entry == null })
    }

    @Test
    fun `条目少于槽位数时多余槽位为空`() {
        val (d, _) = driver()

        d.reset(entries(1, 2, 3), 8, 17)

        assertEquals(3, d.snapshot.slots.count { it.entry != null })
        assertEquals(3, d.snapshot.slots.count { it.entry == null })
    }

    @Test
    fun `日期未变时 dayChanged 为假`() {
        val (d, _) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        d.tick(AUTO_ADVANCE_SECONDS)

        assertFalse(d.dayChanged())
    }

    @Test
    fun `跨午夜后 dayChanged 为真`() {
        val (d, now) = driver(today = LocalDate.of(2026, 8, 17))
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)

        now[0] = LocalDate.of(2026, 8, 18)

        assertTrue(d.dayChanged())
    }

    @Test
    fun `不同随机种子给出不同起点`() {
        val list = entries(*IntArray(100) { it + 1 })
        val starts =
            (0 until 20).map { seed ->
                val d = QueueDriver(clock = { LocalDate.of(2026, 8, 17) }, random = Random(seed))
                d.reset(list, 8, 17)
                d.snapshot.slots[0].entry!!.year
            }

        assertTrue("20 个种子应产生多于 1 个不同起点", starts.toSet().size > 1)
    }

    @Test
    fun `连续多次自动推进累计正确`() {
        val (d, _) = driver()
        d.reset(entries(1, 2, 3, 4, 5, 6, 7, 8), 8, 17)
        val start = d.snapshot.slots[0].entry!!.year

        repeat(3) {
            d.tick(AUTO_ADVANCE_SECONDS)
            d.tick(TRANSITION_SECONDS)
        }

        val expected = ((start - 1 + 3) % 8) + 1
        assertEquals(expected, d.snapshot.slots[0].entry!!.year)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.QueueDriverTest"
```

期望：编译失败，`Unresolved reference: QueueDriver`。

- [ ] **Step 3: 写实现**

创建 `queue/QueueSnapshot.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import tech.illusion.todayinhistory.data.HistoryEntry

/** 某个槽位当前该显示什么；[entry] 为 null 表示该槽位空着（条目不足）。 */
data class SlotContent(val slot: Int, val entry: HistoryEntry?)

/**
 * System 发布给 Compose 的只读快照。
 *
 * 这是 3D 侧到 2D 侧的**单向**通道：面板内容随快照重组，但 3D 变换永远由
 * System 直接写 TransformComponent，不经过 Compose。
 */
data class QueueSnapshot(val slots: List<SlotContent>, val month: Int, val day: Int) {
    companion object {
        val EMPTY =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, null) },
                month = 1,
                day = 1,
            )
    }
}
```

创建 `queue/QueueDriver.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import java.time.LocalDate
import kotlin.random.Random
import tech.illusion.todayinhistory.data.HistoryEntry

/** 无操作时的自动推进间隔。 */
const val AUTO_ADVANCE_SECONDS = 300f

/** 一次推进的过渡时长。 */
const val TRANSITION_SECONDS = 0.6f

/**
 * 队列的全部时间相关逻辑，纯 Kotlin，无 SDK 依赖，因此可以完整单测。
 *
 * ECS 侧（HistoryQueueSystem）只做三件事：每帧调 [tick]、把 [poseOf] 的结果写进
 * TransformComponent、把 [snapshot] 推给 Compose。
 *
 * @param clock 注入当前日期，测试里可控；生产传 `{ LocalDate.now() }`
 */
class QueueDriver(private val clock: () -> LocalDate, private val random: Random) {

    private var queue = HistoryQueue(emptyList(), 0)
    private var loadedDate: LocalDate = clock()

    private var idleSeconds = 0f
    private var transitionSeconds = -1f
    private var transitionStep = 0

    var snapshot: QueueSnapshot = QueueSnapshot.EMPTY
        private set

    val isTransitioning: Boolean
        get() = transitionSeconds >= 0f

    /** 装载某天的数据，随机选起点，重置计时。 */
    fun reset(entries: List<HistoryEntry>, month: Int, day: Int) {
        queue = HistoryQueue.randomStart(entries, random)
        loadedDate = clock()
        idleSeconds = 0f
        transitionSeconds = -1f
        transitionStep = 0
        publish(month, day)
    }

    /** 每帧调用。[deltaSeconds] 来自 SceneUpdateContext.deltaTime。 */
    fun tick(deltaSeconds: Float) {
        if (isTransitioning) {
            transitionSeconds += deltaSeconds
            if (transitionSeconds >= TRANSITION_SECONDS) {
                transitionSeconds = -1f
                transitionStep = 0
            }
            return
        }

        if (queue.size == 0) return

        idleSeconds += deltaSeconds
        if (idleSeconds >= AUTO_ADVANCE_SECONDS) {
            requestAdvance(step = 1)
        }
    }

    /**
     * 请求前进 [step] 条。点焦点卡传 1，点第 i 条待读传 i。
     * 过渡进行中的请求会被忽略，避免动画打架。
     */
    fun requestAdvance(step: Int) {
        if (queue.size == 0 || isTransitioning || step <= 0) return

        queue.advance(step)
        idleSeconds = 0f
        transitionSeconds = 0f
        transitionStep = step
        publish(snapshot.month, snapshot.day)
    }

    /**
     * 第 [slot] 个槽位此刻的姿态。
     *
     * 推进刚发生时，队列已经切换到新内容，但视觉上卡片应该从「旧槽位」滑到「新槽位」，
     * 所以过渡期间从 slot + step 插值到 slot。
     */
    fun poseOf(slot: Int): SlotPose {
        val target = SlotLayout.poseFor(slot)
        if (!isTransitioning) return target

        val origin = SlotLayout.poseFor(slot + transitionStep)
        val t = SlotLayout.easeOutCubic(transitionSeconds / TRANSITION_SECONDS)
        return SlotLayout.lerp(origin, target, t)
    }

    /** 距下一次自动推进还剩多少秒。 */
    fun secondsUntilAutoAdvance(): Float = (AUTO_ADVANCE_SECONDS - idleSeconds).coerceAtLeast(0f)

    /** 系统日期是否已经跨过装载时的那一天——摆件整夜开着时会发生。 */
    fun dayChanged(): Boolean = clock() != loadedDate

    private fun publish(month: Int, day: Int) {
        snapshot =
            QueueSnapshot(
                slots = (0 until VISIBLE_SLOTS).map { SlotContent(it, queue.entryAt(it)) },
                month = month,
                day = day,
            )
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.QueueDriverTest"
```

期望：14 个测试全部 PASS。

- [ ] **Step 5: 跑全部单测**

```bash
./gradlew :app:testDebugUnitTest
```

期望：Task 2/3/4/5 的 43 个测试全部 PASS（模板自带的 `ExampleUnitTest` 也在内）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/queue app/src/test/java/tech/illusion/todayinhistory/queue
git commit -m "feat(queue): 计时、推进与插值驱动器"
```

---

## Task 6: ECS 组件与系统

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/HistoryCardComponent.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/queue/HistoryQueueSystem.kt`

本任务没有 JVM 单测——它是 ECS 胶水层，逻辑已在 Task 5 测过。验收标准是**编译通过**，运行时行为在 Task 8 验证。

**Interfaces:**
- Consumes: `QueueDriver`、`SlotPose`、`QueueSnapshot`、`VISIBLE_SLOTS`
- Produces:
  - `class HistoryCardComponent : Component()`，字段 `var slot: Int = 0`，覆写 `clone()`
  - `object QueueHost` —— System 与 Compose 之间的桥
    - `val snapshots: StateFlow<QueueSnapshot>`
    - `var driver: QueueDriver?`
    - `var boundsMeters: Vector3`
    - `fun onCardClicked(slot: Int)`
  - `class HistoryQueueSystem : System()`，覆写 `update(context: SceneUpdateContext)`

- [ ] **Step 1: 写自定义组件**

创建 `queue/HistoryCardComponent.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import com.pico.spatial.core.ecs.Component

/**
 * 挂在每个卡片 Entity 上，记录它占哪个槽位。
 *
 * 自定义 Component 必须提供无参默认构造（SDK 内部机制要求）。
 */
class HistoryCardComponent : Component() {

    var slot: Int = 0

    override fun clone(): Component = HistoryCardComponent().also { it.slot = slot }
}
```

- [ ] **Step 2: 写 System 与桥接对象**

创建 `queue/HistoryQueueSystem.kt`：

```kotlin
package tech.illusion.todayinhistory.queue

import com.pico.spatial.core.ecs.EntityQueryCondition
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System 与 Compose 之间的桥。
 *
 * System 单向往这里写快照，Compose 单向读。反方向只有一条极窄的通道：
 * [onCardClicked]，把用户点击排进队列，由 System 在下一帧消费。
 *
 * 用 StateFlow 而不是 MutableState：System.update 虽然跑在主线程，
 * 但 StateFlow 的线程安全保证让这条边界不依赖那个假设。
 */
object QueueHost {

    private val _snapshots = MutableStateFlow(QueueSnapshot.EMPTY)
    val snapshots: StateFlow<QueueSnapshot> = _snapshots.asStateFlow()

    /** 由 HomeVolume 在数据装载完成后注入。 */
    @Volatile var driver: QueueDriver? = null

    /** SpatialView 包围盒尺寸（米），由 HomeVolume 在 onSizeChanged 时更新。 */
    @Volatile var boundsMeters: Vector3 = Vector3(1f, 1f, 1f)

    /** 待消费的点击请求：前进多少条。0 表示没有待处理请求。 */
    @Volatile private var pendingStep: Int = 0

    /** 供请求重新装载数据的回调（跨午夜时由 System 触发）。 */
    @Volatile var onDayChanged: (() -> Unit)? = null

    fun onCardClicked(slot: Int) {
        // 点焦点卡（slot 0）也前进一条；点第 i 条待读直接跳 i 条。
        pendingStep = if (slot <= 0) 1 else slot
    }

    internal fun consumePendingStep(): Int {
        val step = pendingStep
        pendingStep = 0
        return step
    }

    internal fun publish(snapshot: QueueSnapshot) {
        _snapshots.value = snapshot
    }

    /** 容器销毁时清空，避免持有过期的 driver。 */
    fun clear() {
        driver = null
        onDayChanged = null
        pendingStep = 0
        _snapshots.value = QueueSnapshot.EMPTY
    }
}

/**
 * 每帧驱动队列：消费点击、推进计时、把插值出来的姿态写进 TransformComponent。
 *
 * 严格约束（SDK 要求）：update 跑在主线程，不得阻塞、不得起协程、不得跨线程调 ECS API。
 * 因此跨午夜的重新装载只是**发一个信号**，实际的 IO 由 HomeVolume 侧的协程完成。
 */
class HistoryQueueSystem : System() {

    private var lastPublished: QueueSnapshot? = null

    override fun update(context: SceneUpdateContext) {
        val driver = QueueHost.driver ?: return super.update(context)

        val step = QueueHost.consumePendingStep()
        if (step > 0) {
            driver.requestAdvance(step)
        }

        driver.tick(context.deltaTime)

        if (driver.dayChanged()) {
            QueueHost.onDayChanged?.invoke()
        }

        val snapshot = driver.snapshot
        if (snapshot !== lastPublished) {
            lastPublished = snapshot
            QueueHost.publish(snapshot)
        }

        applyPoses(context, driver)

        super.update(context)
    }

    private fun applyPoses(context: SceneUpdateContext, driver: QueueDriver) {
        val bounds = QueueHost.boundsMeters
        val condition = EntityQueryCondition.hasComponent(HistoryCardComponent::class.java)

        context.scene.queryEntity(condition).forEach { entity ->
            val card = entity.components[HistoryCardComponent::class.java] ?: return@forEach
            val transform = entity.components[TransformComponent::class.java] ?: return@forEach
            val pose = driver.poseOf(card.slot)

            transform.position = Vector3(0f, pose.yFactor * bounds.y, pose.zFactor * bounds.z)
            transform.scaleVector = Vector3(pose.scale, pose.scale, pose.scale)
        }
    }
}
```

- [ ] **Step 3: 编译**

```bash
./gradlew :app:compileDebugKotlin
```

期望：BUILD SUCCESSFUL。若出现 `Unresolved reference`，说明该 SDK 符号名与 6.0.0 的实际导出不符——按报错逐个核对
`~/Library/pico/sdk/6.0/agent-vault/spatial/api-reference/com.pico.spatial.core.ecs.md` 里的签名后修正，
**不要臆造 API 名**。

- [ ] **Step 4: 确认全部单测仍然通过**

```bash
./gradlew :app:testDebugUnitTest
```

期望：全部 PASS（本任务没加测试，但不能弄坏已有的）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/queue/HistoryCardComponent.kt app/src/main/java/tech/illusion/todayinhistory/queue/HistoryQueueSystem.kt
git commit -m "feat(ecs): 卡片组件与队列驱动系统"
```

---

## Task 7: SpatialUI 面板

**Files:**
- Create: `app/src/main/java/tech/illusion/todayinhistory/panels/YearLabel.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/panels/DatePanel.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/panels/FocusCard.kt`
- Create: `app/src/main/java/tech/illusion/todayinhistory/panels/WaitingStrip.kt`
- Test: `app/src/test/java/tech/illusion/todayinhistory/panels/YearLabelTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `HistoryEntry`、`Kind`
- Produces:
  - `fun formatYear(year: Int): String` —— 负数转「公元前 N 年」
  - `fun labelOf(kind: Kind): Int` —— 返回 string 资源 id
  - `fun colorOf(kind: Kind): Color`
  - `@Composable fun DatePanel(month: Int, day: Int)`
  - `@Composable fun FocusCard(entry: HistoryEntry?, onClick: () -> Unit)`
  - `@Composable fun WaitingStrip(entry: HistoryEntry?, onClick: () -> Unit)`

- [ ] **Step 1: 写年份格式化的失败测试**

创建 `app/src/test/java/tech/illusion/todayinhistory/panels/YearLabelTest.kt`：

```kotlin
package tech.illusion.todayinhistory.panels

import org.junit.Assert.assertEquals
import org.junit.Test

class YearLabelTest {

    @Test
    fun `公元后年份直接加年字`() {
        assertEquals("1945 年", formatYear(1945))
        assertEquals("1 年", formatYear(1))
    }

    @Test
    fun `公元前年份取绝对值并加前缀`() {
        assertEquals("公元前 1046 年", formatYear(-1046))
        assertEquals("公元前 4713 年", formatYear(-4713))
    }

    @Test
    fun `零年按公元前处理`() {
        assertEquals("公元前 1 年", formatYear(0))
    }
}
```

历法上不存在「公元 0 年」，天文纪年的 0 对应公元前 1 年。上游数据里未见 0，但解析器允许它通过，所以定义清楚。

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.panels.YearLabelTest"
```

期望：编译失败，`Unresolved reference: formatYear`。

- [ ] **Step 3: 加字符串资源**

在 `app/src/main/res/values/strings.xml` 的 `<resources>` 内追加（保留已有条目）：

```xml
    <string name="kind_event">大事记</string>
    <string name="kind_birth">出生</string>
    <string name="kind_death">逝世</string>
    <string name="date_format">%1$d月%2$d日</string>
    <string name="year_ce">%1$d 年</string>
    <string name="year_bce">公元前 %1$d 年</string>
    <string name="empty_day">今天没有找到记录</string>
```

- [ ] **Step 4: 写实现**

创建 `panels/YearLabel.kt`（纯逻辑，不依赖 Compose，因此可单测）：

```kotlin
package tech.illusion.todayinhistory.panels

import kotlin.math.abs

/**
 * 把年份整数格式化成展示文本。
 *
 * 负数是公元前；天文纪年的 0 对应公元前 1 年，所以 0 也按公元前处理。
 * 这里不走 string 资源，是为了能在 JVM 单测里直接验证。
 */
fun formatYear(year: Int): String =
    when {
        year > 0 -> "$year 年"
        year == 0 -> "公元前 1 年"
        else -> "公元前 ${abs(year)} 年"
    }
```

创建 `panels/DatePanel.kt`：

```kotlin
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

/** 日期牌：钉在最前上方，不参与队列推进。 */
@Composable
fun DatePanel(month: Int, day: Int) {
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
            color = PicoTheme.colors.textPrimary,
        )
    }
}
```

创建 `panels/FocusCard.kt`：

```kotlin
package tech.illusion.todayinhistory.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.todayinhistory.R
import tech.illusion.todayinhistory.data.HistoryEntry
import tech.illusion.todayinhistory.data.Kind

/** 类型标签的强调色。 */
fun colorOf(kind: Kind): Color =
    when (kind) {
        Kind.EVENT -> Color(0xFF7B95FF)
        Kind.BIRTH -> Color(0xFF5AC88C)
        Kind.DEATH -> Color(0xFFBE82E6)
    }

fun labelOf(kind: Kind): Int =
    when (kind) {
        Kind.EVENT -> R.string.kind_event
        Kind.BIRTH -> R.string.kind_birth
        Kind.DEATH -> R.string.kind_death
    }

/**
 * 焦点卡：队列里唯一展开正文的一张。
 *
 * 正文长度跨度极大（实测中位 18 字、p99 75 字、最长 402 字），
 * 所以短内容必须看起来是**刻意的**而非空的：年份大字 + 类型标签 + 留白共同成立。
 * 超长的极少数走内部滚动。
 */
@Composable
fun FocusCard(entry: HistoryEntry?, onClick: () -> Unit) {
    Column(
        modifier =
            Modifier.width(560.dp)
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
                color = PicoTheme.colors.textSecondary,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier.size(10.dp).clip(CircleShape).backgroundMaterial(false, Material.Regular)
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
            color = PicoTheme.colors.textPrimary,
        )

        Text(
            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            text = entry.text,
            style = PicoTheme.typography.bodyLarge,
            color = PicoTheme.colors.textSecondary,
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}
```

创建 `panels/WaitingStrip.kt`：

```kotlin
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.todayinhistory.data.HistoryEntry

/** 待读条：只有年份和一行文本，点击可直接跳到它。 */
@Composable
fun WaitingStrip(entry: HistoryEntry?, onClick: () -> Unit) {
    if (entry == null) return

    Row(
        modifier =
            Modifier.width(520.dp)
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
            color = PicoTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

⚠️ `PicoTheme.colors.*`、`PicoTheme.typography.displayMedium/bodyLarge/bodyMedium/labelLarge`、
`Material.Thick`、`backgroundMaterial` 的确切成员名以 SDK 6.0.0 实际导出为准。编译报错时，
到 `~/Library/pico/sdk/6.0/agent-vault/spatial/api-reference/com.pico.spatial.ui.design.tokens.md`
和 `com.pico.spatial.ui.design.md` 里查实际可用的 token 名再替换；
脚手架 `HomeVolume.kt` 里已验证可用的是 `PicoTheme.typography.titleLarge`、`headlineLarge`
和 `backgroundMaterial(true, Material.Regular)`，可作为保底。

- [ ] **Step 5: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.panels.YearLabelTest"
```

期望：3 个测试全部 PASS。

- [ ] **Step 6: 编译**

```bash
./gradlew :app:compileDebugKotlin
```

期望：BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/panels app/src/test/java/tech/illusion/todayinhistory/panels app/src/main/res/values/strings.xml
git commit -m "feat(ui): 日期牌、焦点卡与待读条面板"
```

---

## Task 8: 组装 HomeVolume 并首次跑起来

**Files:**
- Modify: `app/src/main/java/tech/illusion/todayinhistory/content/HomeVolume.kt`（整体重写）

**Interfaces:**
- Consumes: 前面全部
- Produces: `@Composable fun HomeVolume()`（签名不变，`Main.kt` 无需改动）

- [ ] **Step 1: 重写 HomeVolume**

把 `content/HomeVolume.kt` 整个替换为：

```kotlin
package tech.illusion.todayinhistory.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.unit.LengthUnit
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import java.time.LocalDate
import kotlin.random.Random
import tech.illusion.todayinhistory.data.HistoryRepository
import tech.illusion.todayinhistory.data.asAssetSource
import tech.illusion.todayinhistory.panels.DatePanel
import tech.illusion.todayinhistory.panels.FocusCard
import tech.illusion.todayinhistory.panels.WaitingStrip
import tech.illusion.todayinhistory.queue.HistoryCardComponent
import tech.illusion.todayinhistory.queue.HistoryQueueSystem
import tech.illusion.todayinhistory.queue.QueueDriver
import tech.illusion.todayinhistory.queue.QueueHost
import tech.illusion.todayinhistory.queue.SlotLayout
import tech.illusion.todayinhistory.queue.VISIBLE_SLOTS

private const val DATE_ATTACHMENT = "date_panel"
private const val CARD_ATTACHMENT_PREFIX = "card_"

@Composable
fun HomeVolume() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val converter = LocalPhysicalLengthConverter.current

    val repository = remember { HistoryRepository(context.assets.asAssetSource()) }
    val driver = remember { QueueDriver(clock = { LocalDate.now() }, random = Random.Default) }

    // 递增这个值即可触发重新装载——跨午夜时 System 会请求它。
    var reloadToken by remember { mutableStateOf(0) }

    val snapshot by QueueHost.snapshots.collectAsState()

    LaunchedEffect(reloadToken) {
        val today = LocalDate.now()
        val entries = repository.loadDay(today.monthValue, today.dayOfMonth)
        driver.reset(entries, today.monthValue, today.dayOfMonth)
        QueueHost.driver = driver
    }

    DisposableEffect(Unit) {
        QueueHost.onDayChanged = { reloadToken++ }
        registerSystem<HistoryQueueSystem>()
        onDispose {
            unregisterSystem<HistoryQueueSystem>()
            QueueHost.clear()
        }
    }

    SpatialView(
        modifier =
            Modifier.onSizeChanged { size ->
                // px -> 米。SpatialView 的包围盒深度按较短边估算，实际值在 Task 9 调优。
                with(density) {
                    val widthMeters =
                        converter.dpToLength(size.width.toDp(), LengthUnit.Meters)
                    val heightMeters =
                        converter.dpToLength(size.height.toDp(), LengthUnit.Meters)
                    QueueHost.boundsMeters =
                        Vector3(widthMeters, heightMeters, minOf(widthMeters, heightMeters))
                }
            },
        initial = { content, attachments ->
            // 日期牌：固定姿态，不挂 HistoryCardComponent，因此不被 System 移动。
            attachments.entity(DATE_ATTACHMENT)?.apply {
                val bounds = QueueHost.boundsMeters
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(
                        0f,
                        SlotLayout.datePose.yFactor * bounds.y,
                        SlotLayout.datePose.zFactor * bounds.z,
                    )
                )
                content.addEntity(this)
            }

            // 6 张卡片：挂上 HistoryCardComponent，之后完全由 System 驱动位置。
            for (slot in 0 until VISIBLE_SLOTS) {
                attachments.entity("$CARD_ATTACHMENT_PREFIX$slot")?.apply {
                    components.set(HistoryCardComponent().also { it.slot = slot })
                    content.addEntity(this)
                }
            }
        },
        attachments = {
            AttachmentPanel(id = DATE_ATTACHMENT) {
                DatePanel(month = snapshot.month, day = snapshot.day)
            }

            for (slot in 0 until VISIBLE_SLOTS) {
                AttachmentPanel(id = "$CARD_ATTACHMENT_PREFIX$slot") {
                    val entry = snapshot.slots.getOrNull(slot)?.entry
                    if (slot == 0) {
                        FocusCard(entry = entry, onClick = { QueueHost.onCardClicked(0) })
                    } else {
                        WaitingStrip(entry = entry, onClick = { QueueHost.onCardClicked(slot) })
                    }
                }
            }
        },
    )
}
```

`registerSystem` / `unregisterSystem` 的导入包名以编译结果为准；SDK 文档示例中它们是
`@Composable` 作用域内的顶层函数。若 `com.pico.spatial.ui.foundation.dsl` 下找不到，
用 IDE 的自动导入解析，或退回 `System.register(HistoryQueueSystem::class.java)` /
`System.unregister(...)`（api-reference 中已确认存在的静态方法）。

- [ ] **Step 2: 编译**

```bash
./gradlew :app:assembleDebug
```

期望：BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 确认没有引入 Material**

```bash
grep -rn "androidx.compose.material\|MaterialTheme" app/src/main/java && echo "发现 Material，必须移除" || echo "无 Material，通过"
```

期望：`无 Material，通过`。

- [ ] **Step 4: 启动模拟器并安装**

按 `spatial-emulator-usage` 技能的流程操作：

```bash
pico-cli emulator list
pico-cli emulator start
pico-cli device list
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app launch --package tech.illusion.todayinhistory
```

- [ ] **Step 5: 截图确认渲染**

```bash
pico-cli device screenshot --output /tmp/tih-first-run.png
```

查看截图，逐项确认：
1. 日期牌显示今天的月日
2. 焦点卡有年份大字、类型标签、正文
3. 后方能看到若干条待读条，越远越淡
4. 所有面板都在容器边界内

- [ ] **Step 6: 检查崩溃**

```bash
pico-cli device logcat --filter "tech.illusion.todayinhistory" > /tmp/tih-logcat.txt
grep -iE "FATAL|AndroidRuntime|Exception" /tmp/tih-logcat.txt || echo "无崩溃"
```

期望：`无崩溃`。若有崩溃，读堆栈定位后修复，重跑 Step 2-6。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/todayinhistory/content/HomeVolume.kt
git commit -m "feat(content): 组装 Volume 队列场景"
```

---

## Task 9: 设备验证与调优

**Files:**
- Modify: `app/src/main/java/tech/illusion/todayinhistory/queue/SlotLayout.kt`（按实测调整数值）

本任务没有新代码，全是在设备上验证 spec §10.2 列出的项目，并把 `SlotLayout` 的数值调到位。

- [ ] **Step 1: 验证点击命中区域**

这是 spec §7 明确标注的未验证项。在模拟器里依次点击焦点卡和每一条待读条，每次点击后截图：

```bash
pico-cli device screenshot --output /tmp/tih-click-N.png
```

确认：
- 点焦点卡 → 内容前进一条
- 点第 3 条待读 → 直接跳 3 条（原第 3 条变成焦点）
- 点击响应区域与卡片视觉边界一致，没有点不到或误触

**如果命中区域不对**：给卡片实体加 `InteractableComponent` + `CollisionComponent`，
改走 `detectTapGestures(context = context, targetedToEntity = TargetEntity.hit(entity))`，
参考 `~/Library/pico/sdk/6.0/agent-vault/spatial/documentation/spatial-sdk_interaction_interact-with-entities.md`。

- [ ] **Step 2: 验证自动推进**

把 `AUTO_ADVANCE_SECONDS` 临时改成 `5f`，重新构建安装，观察是否每 5 秒前进一条且过渡动画流畅：

```bash
./gradlew :app:assembleDebug && pico-cli app install --reinstall app/build/outputs/apk/debug/app-debug.apk
pico-cli device record --duration 20 --output /tmp/tih-auto.mp4
```

确认无误后**改回 `300f`** 并重新构建安装。

- [ ] **Step 3: 验证手动点击重置计时**

`AUTO_ADVANCE_SECONDS` 仍为 `5f` 时：点一下卡片，然后数 5 秒，确认下一次自动推进是从点击那一刻起算，而不是接着原来的倒计时。验证完改回 `300f`。

- [ ] **Step 4: 调优槽位数值**

对着截图检查并修改 `SlotLayout.poses`：
- 最远的待读条文字是否还能认出来？看不清就调高 `alpha` 或 `scale`
- 焦点卡是否有从容器前面穿出去？穿出就调低 `zFactor`
- 卡片之间是否有不该有的重叠？重叠就拉开 `yFactor` 的间距

每改一轮：

```bash
./gradlew :app:testDebugUnitTest --tests "tech.illusion.todayinhistory.queue.SlotLayoutTest"
./gradlew :app:assembleDebug && pico-cli app install --reinstall app/build/outputs/apk/debug/app-debug.apk
pico-cli device screenshot --output /tmp/tih-tune-N.png
```

`SlotLayoutTest` 里的单调性和范围断言会守住调优不跑偏。

- [ ] **Step 5: 验证跨午夜**

`QueueDriver` 的跨午夜逻辑已有单测覆盖（注入时钟）。设备侧做一次端到端确认：

```bash
pico-cli device shell "date"                        # 记下当前日期
pico-cli device shell "su 0 date MMDDhhmm"          # 把系统日期拨到次日
pico-cli device screenshot --output /tmp/tih-midnight.png
```

确认日期牌换成了新的一天、队列内容整体换了。做完把设备日期改回去。
若模拟器不允许改系统时间，记录该项**在设备上未验证**，单测覆盖仍然有效。

- [ ] **Step 6: 长稳观察**

`AUTO_ADVANCE_SECONDS` 恢复 `300f` 后让应用挂 30 分钟，然后：

```bash
pico-cli device logcat --filter "tech.illusion.todayinhistory" > /tmp/tih-soak.txt
grep -iE "FATAL|Exception|leak|OutOfMemory" /tmp/tih-soak.txt || echo "长稳通过"
```

确认已推进约 6 条，无崩溃、无泄漏告警。

- [ ] **Step 7: 收尾提交**

```bash
./gradlew :app:testDebugUnitTest    # 全部 PASS
git add -A
git commit -m "fix(queue): 依据设备实测调整槽位姿态"
```

- [ ] **Step 8: 更新 spec 的遗留项**

把 spec §12 中已经验证的条目划掉，把 Step 1 和 Step 5 的实际结论写进去（尤其是命中区域最终走了哪条路、跨午夜是否在设备上验过）。

```bash
git add docs/superpowers/specs/2026-08-17-today-in-history-volume-design.md
git commit -m "docs: 记录设备验证结论"
```

---

## Self-Review

**Spec 覆盖检查**

| Spec 章节 | 对应任务 |
|---|---|
| §2 数据源 / §2.1 实测 / §2.2 三个坑 | Task 1 |
| §3.1 全量打包 | Task 1 |
| §3.2 纵深队列 | Task 4、Task 8 |
| §3.3 单队列混排 | Task 1（排序）、Task 3 |
| §3.4 保序 + 随机起点 | Task 3（`randomStart`）、Task 5 |
| §3.5 ECS 拥有状态 | Task 5、Task 6 |
| §4 运行时结构 / §4.1 推进逻辑 | Task 5、Task 6、Task 8 |
| §4.2 动画 | Task 4（缓动）、Task 5（插值）——**已声明偏离** |
| §4.3 生命周期 | Task 8（`DisposableEffect`） |
| §5 数据层 | Task 1、Task 2 |
| §6 空间布局 / §6.1 焦点卡排版 / §6.2 主题 | Task 4、Task 7、Task 9 |
| §7 交互 | Task 7（`clickable`）、Task 8（路由）、Task 9（验证） |
| §8 容器配置 | Global Constraints（不改 Manifest）、Task 8（不加载 MyScene） |
| §9 错误处理 | Task 2（IO/解析）、Task 3（空队列/越界）、Task 5（跨午夜）、Task 7（占位卡） |
| §10 验证 | Task 2/3/4/5/7 的单测 + Task 8/9 的设备验证 |
| §11 文件清单 | File Structure |

无遗漏。§4.2 的偏离已在文首显式声明并给出理由。

**Placeholder 扫描**：无 TBD / TODO / 「类似 Task N」/ 无代码的代码步骤。三处 ⚠️ 均为「SDK 符号名以实际导出为准 + 明确的查证路径与保底方案」，不是占位符。

**类型一致性**：`HistoryEntry(year, text, kind)` 在 Task 2/3/5/7 中一致；`Kind.EVENT/BIRTH/DEATH` 一致；`VISIBLE_SLOTS` 在 Task 3 定义，Task 4/5/6/8 引用；`SlotPose` 字段 `zFactor/yFactor/scale/alpha` 在 Task 4/5/6 一致；`QueueSnapshot(slots, month, day)` 在 Task 5/8 一致；`QueueHost` 的成员在 Task 6/8 一致；`AUTO_ADVANCE_SECONDS`/`TRANSITION_SECONDS` 在 Task 5/9 一致。
