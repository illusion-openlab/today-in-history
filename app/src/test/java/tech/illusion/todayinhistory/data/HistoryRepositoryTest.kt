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
