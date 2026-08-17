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
