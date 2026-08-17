package tech.illusion.todayinhistory.data

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAssets(private val files: Map<String, String>) : AssetSource {
    override fun open(path: String): InputStream {
        val body = files[path] ?: throw FileNotFoundException(path)
        return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}

/**
 * 单个反斜杠。下面几个 `\uXXXX` 输入都用它拼接而成，这样源码里不会出现
 * 任何可能被编辑器或工具链悄悄归一化掉的转义序列——喂给解析器的字节是确定的。
 */
private const val BS = "\\"

private val RAW_U_LOWER = """[[1900,"${BS}u4e2d","e"]]"""
private val RAW_U_UPPER = """[[1900,"${BS}u4E2D","e"]]"""
private val RAW_U_ASCII = """[[1900,"${BS}u0041${BS}u0030","e"]]"""
private val RAW_U_SURROGATE = """[[1900,"笑${BS}ud83d${BS}ude00脸","e"]]"""

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

    // ---- 转义分支覆盖（I-1）----
    // 真实数据用 ensure_ascii=False 生成，这些分支在线上走不到，是纯防御代码，
    // 所以更需要测试钉死——没有覆盖的防御代码是最容易悄悄坏掉的。

    private suspend fun parseSingleText(json: String): String {
        val repo = HistoryRepository(FakeAssets(mapOf("history/6-1.json" to json)))
        return repo.loadDay(6, 1).single().text
    }

    @Test
    fun `解析两字符转义`() = runBlocking {
        assertEquals("a\nb", parseSingleText("""[[1900,"a\nb","e"]]"""))
        assertEquals("a\tb", parseSingleText("""[[1900,"a\tb","e"]]"""))
        assertEquals("a\rb", parseSingleText("""[[1900,"a\rb","e"]]"""))
        assertEquals("a\bb", parseSingleText("""[[1900,"a\bb","e"]]"""))
        assertEquals("ab", parseSingleText("""[[1900,"a\fb","e"]]"""))
        assertEquals("a\\b", parseSingleText("""[[1900,"a\\b","e"]]"""))
        assertEquals("a\"b", parseSingleText("""[[1900,"a\"b","e"]]"""))
        assertEquals("a/b", parseSingleText("""[[1900,"a\/b","e"]]"""))
    }

    @Test
    fun `解析反斜杠结尾的正文`() = runBlocking {
        // 真实资产 11-18.json 里确实有这么一条（以单个反斜杠结尾）。
        assertEquals("结尾是反斜杠\\", parseSingleText("""[[1900,"结尾是反斜杠\\","e"]]"""))
    }

    @Test
    fun `解析 unicode 转义`() = runBlocking {
        // 三引号原始字符串不做任何转义处理，所以下面这些是字面的 6 个字符 \ u X X X X，
        // 正是解析器要处理的输入。
        assertEquals("中", parseSingleText(RAW_U_LOWER))
        assertEquals("中", parseSingleText(RAW_U_UPPER))
        assertEquals("A0", parseSingleText(RAW_U_ASCII))
    }

    @Test
    fun `unicode 代理对还原成单个 emoji`() = runBlocking {
        val text = parseSingleText(RAW_U_SURROGATE)

        assertEquals("笑😀脸", text)
        // 笑(1) + emoji(2) + 脸(1) = 4 个 Char，但只有 3 个码点——证明代理对被正确合成，
        // 而不是留下两个孤立的 surrogate。
        assertEquals(4, text.length)
        assertEquals(3, text.codePointCount(0, text.length))
        assertEquals(0x1F600, text.codePointAt(1))
    }

    // ---- Kind.fromCode 直接单元测试（I-1）----

    @Test
    fun `Kind fromCode 映射合法类型码`() {
        assertEquals(Kind.EVENT, Kind.fromCode("e"))
        assertEquals(Kind.BIRTH, Kind.fromCode("b"))
        assertEquals(Kind.DEATH, Kind.fromCode("d"))
    }

    @Test
    fun `Kind fromCode 对非法类型码返回 null`() {
        assertNull(Kind.fromCode("x"))
        assertNull(Kind.fromCode(""))
        assertNull(Kind.fromCode("E"))
        assertNull(Kind.fromCode("event"))
        assertNull(Kind.fromCode(" e"))
    }

    // ---- 单条畸形数据只丢单条（I-2）----

    @Test
    fun `年份超出 Int 范围时只丢该条`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(
                mapOf("history/7-1.json" to """[[99999999999,"bad","e"],[1900,"keep","e"]]""")
            )
        )

        val entries = repo.loadDay(7, 1)

        assertEquals(1, entries.size)
        assertEquals(HistoryEntry(1900, "keep", Kind.EVENT), entries.single())
    }

    @Test
    fun `裸负号年份时只丢该条`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/7-2.json" to """[[-,"bad","e"],[1900,"keep","e"]]"""))
        )

        assertEquals(listOf(1900), repo.loadDay(7, 2).map { it.year })
    }

    @Test
    fun `小数年份时只丢该条`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/7-3.json" to """[[1900.5,"bad","e"],[1901,"keep","e"]]"""))
        )

        assertEquals(listOf(1901), repo.loadDay(7, 3).map { it.year })
    }

    @Test
    fun `跳过损坏条目时尊重正文里的引号与方括号`() = runBlocking {
        // 损坏条目的正文里同时含有 ] 和转义引号，简单地找下一个 ] 会在这里错位。
        val repo = HistoryRepository(
            FakeAssets(
                mapOf(
                    "history/7-4.json" to
                        """[[99999999999,"含有 ] 和 \" 的正文","e"],[1902,"keep","e"]]"""
                )
            )
        )

        val entries = repo.loadDay(7, 4)

        assertEquals(1, entries.size)
        assertEquals("keep", entries.single().text)
    }

    @Test
    fun `多条损坏时保留全部正常条目`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(
                mapOf(
                    "history/7-5.json" to
                        """[[1900,"ok1","e"],[99999999999,"bad","e"],[1901,"ok2","b"],""" +
                        """[1902,"skip","x"],[-,"bad2","e"],[1903,"ok3","d"]]"""
                )
            )
        )

        val entries = repo.loadDay(7, 5)

        assertEquals(listOf(1900, 1901, 1903), entries.map { it.year })
        assertEquals(
            listOf(Kind.EVENT, Kind.BIRTH, Kind.DEATH),
            entries.map { it.kind }
        )
    }

    @Test
    fun `正文未闭合仍然放弃整个文件`() = runBlocking {
        // 与「只丢单条」不冲突：字符串没有收尾引号，条目边界根本无法确定，
        // 继续猜下去只会产出随机结果，此时放弃整个文件才是确定性行为。
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/7-6.json" to """[[1900,"ok","e"],[1901,"缺少收尾"""))
        )

        assertTrue(repo.loadDay(7, 6).isEmpty())
    }

    // ---- Minor ----

    @Test
    fun `尾随垃圾视为损坏`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/9-1.json" to """[[1900,"x","e"]]}}}随便什么垃圾"""))
        )

        assertTrue(repo.loadDay(9, 1).isEmpty())
    }

    @Test
    fun `空数组后的尾随垃圾也视为损坏`() = runBlocking {
        val repo = HistoryRepository(FakeAssets(mapOf("history/9-2.json" to "[] 垃圾")))

        assertTrue(repo.loadDay(9, 2).isEmpty())
    }

    @Test
    fun `收尾后的空白仍然合法`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(mapOf("history/9-3.json" to "[[1900,\"x\",\"e\"]]\n  \n"))
        )

        assertEquals(1, repo.loadDay(9, 3).size)
    }

    @Test
    fun `unicode 转义带符号位时只丢该条`() = runBlocking {
        // "\u-041" 过去会走 "-041".toInt(16) = -65，截断成 Char 后产出垃圾字符 U+FFBF。
        val repo = HistoryRepository(
            FakeAssets(
                mapOf("history/9-4.json" to """[[1900,"\u-041","e"],[1901,"keep","e"]]""")
            )
        )

        val entries = repo.loadDay(9, 4)

        assertEquals(1, entries.size)
        assertEquals("keep", entries.single().text)
    }

    @Test
    fun `unicode 转义位数不足时只丢该条`() = runBlocking {
        val repo = HistoryRepository(
            FakeAssets(
                mapOf("history/9-5.json" to """[[1900,"\u12g4","e"],[1901,"keep","e"]]""")
            )
        )

        assertEquals(listOf(1901), repo.loadDay(9, 5).map { it.year })
    }

    @Test
    fun `非法转义视为损坏而不是静默吞掉反斜杠`() = runBlocking {
        // 过去 "\q" 会得到 "q"，掩盖上游生成脚本的转义 bug。
        val repo = HistoryRepository(
            FakeAssets(
                mapOf("history/9-6.json" to """[[1900,"a\qb","e"],[1901,"keep","e"]]""")
            )
        )

        val entries = repo.loadDay(9, 6)

        assertEquals(1, entries.size)
        assertEquals("keep", entries.single().text)
    }
}
