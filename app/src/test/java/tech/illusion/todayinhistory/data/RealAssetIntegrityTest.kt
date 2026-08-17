package tech.illusion.todayinhistory.data

import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实资产的常驻回归测试。
 *
 * 存在的理由：`HistoryRepositoryTest` 里的用例全部喂手写 fixture，**一条都不碰
 * Task 1 生成的真实数据**。而解析器在修复轮 1 被收紧过（尾随非空白字节零容忍、
 * 非法转义直接拒绝、`\uXXXX` 逐位校验），这些严格化如果和生成脚本产生分歧，
 * 表现是**每一天都静默返回空列表**——摆件打开就是白板，而单测照样全绿。
 *
 * 这条测试就是那张安全网：它红了即意味着「资产管线和解析器不兼容」。
 *
 * 这也正是 [AssetSource] 抽象存在的意义——JVM 单测不需要 Android instrumentation，
 * 直接用 [FileInputStream] 就能读到项目目录里的真实 assets。
 */
class RealAssetIntegrityTest {

    /** Task 1 的产物规模。这两个数字变了说明资产管线变了，应该是一次显式决策而不是意外。 */
    private val expectedFileCount = 366
    private val expectedEntryCount = 54018

    /**
     * 定位 assets 根目录。
     *
     * 不写死绝对路径：Gradle 单测的工作目录实测是**模块目录**（`<repo>/app`），
     * 但为了在仓库根启动测试时也能工作，这里对两种 CWD 都做了尝试，
     * 失败时把实际 CWD 和试过的候选路径一起报出来。
     */
    private fun locateAssetsRoot(): File {
        val cwd = File("").absoluteFile
        val candidates =
            listOf(
                File(cwd, "src/main/assets"), // CWD = <repo>/app（Gradle 单测默认）
                File(cwd, "app/src/main/assets"), // CWD = <repo>
            )
        return candidates.firstOrNull { File(it, "history").isDirectory }
            ?: throw AssertionError(
                "定位不到 assets 目录。CWD=$cwd；试过：" +
                    candidates.joinToString(", ") { it.path }
            )
    }

    @Test
    fun `真实资产全部可被解析且规模符合预期`() = runBlocking {
        val assetsRoot = locateAssetsRoot()
        println("REAL-ASSET CWD=${File("").absoluteFile}")
        println("REAL-ASSET assetsRoot=$assetsRoot")

        val repo = HistoryRepository(AssetSource { path -> FileInputStream(File(assetsRoot, path)) })

        val files =
            File(assetsRoot, "history")
                .listFiles { _, name -> name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: throw AssertionError("history/ 目录不可枚举")

        // 每个问题都带文件名，红的时候能直接定位到是哪一天，而不是只看到一个总数不符。
        val problems = mutableListOf<String>()
        var totalEntries = 0

        for (file in files) {
            val parts = file.name.removeSuffix(".json").split("-")
            val month = parts.getOrNull(0)?.toIntOrNull()
            val day = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || month == null || day == null) {
                problems += "${file.name}: 文件名不是 {M}-{D}.json 形式"
                continue
            }

            // 顺带钉死「不补零」的路径契约：loadDay 拼的是 "history/$month-$day.json"，
            // 若资产改成 01-05.json，这里会读不到文件而落到「解析结果为空」。
            val entries = repo.loadDay(month, day)

            if (entries.isEmpty()) {
                problems += "${file.name}: 解析结果为空（文件读不到、JSON 被拒绝、或内容为空数组）"
                continue
            }
            totalEntries += entries.size

            entries.forEachIndexed { index, entry ->
                if (entry.text.isBlank()) {
                    problems += "${file.name}[$index]: 正文为空白（年份 ${entry.year}）"
                }
            }

            for (index in 1 until entries.size) {
                if (entries[index].year < entries[index - 1].year) {
                    problems +=
                        "${file.name}[$index]: 年份非升序 " +
                            "(${entries[index - 1].year} -> ${entries[index].year})"
                    break // 每个文件只报第一处逆序，避免刷屏
                }
            }
        }

        assertTrue(
            "发现 ${problems.size} 个问题（最多列出前 20 条）：\n" +
                problems.take(20).joinToString("\n"),
            problems.isEmpty()
        )
        assertEquals("history/ 下的 .json 文件数", expectedFileCount, files.size)
        assertEquals("解析出的条目总数", expectedEntryCount, totalEntries)

        println("REAL-ASSET files=${files.size} entries=$totalEntries")
    }
}
