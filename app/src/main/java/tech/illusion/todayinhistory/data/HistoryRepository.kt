package tech.illusion.todayinhistory.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "HistoryRepository"

/**
 * 记一条警告。
 *
 * `android.util.Log` 在 JVM 单元测试里是「所有方法抛异常」的桩（Method w in android.util.Log
 * not mocked），要让它可用就得改 build.gradle.kts 的 testOptions，而 Global Constraints
 * 禁止改构建文件。所以真机走 Log，单测里捕获桩异常后退回 stderr——两边都能看到诊断信息。
 */
internal fun logWarn(message: String, cause: Throwable) {
    try {
        Log.w(TAG, message, cause)
    } catch (stub: Throwable) {
        System.err.println("W/$TAG: $message -- $cause")
    }
}

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
                    logWarn("读取 $path 失败，当天返回空列表", e)
                    return@withContext emptyList()
                }

            try {
                parse(text)
            } catch (e: Exception) {
                logWarn("解析 $path 失败，当天返回空列表", e)
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
            val digitsStart = i
            while (i < text.length && text[i] in '0'..'9') i++
            // 注意比的是 digitsStart 而不是 start：裸负号 "-" 也必须被拒绝。
            require(i > digitsStart) { "位置 $start 期望整数" }
            val raw = text.substring(start, i)
            return raw.toIntOrNull() ?: throw IllegalArgumentException("位置 $start 的年份超出 Int 范围: $raw")
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
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                require(i + 4 < text.length) { "\\u 转义不完整" }
                                // 逐位校验：不能用 toInt(16)，它会接受 "-041" 这种带符号位的
                                // 输入并在截断成 Char 后产出垃圾字符。
                                var code = 0
                                for (k in 1..4) {
                                    val h = text[i + k]
                                    val d =
                                        when (h) {
                                            in '0'..'9' -> h - '0'
                                            in 'a'..'f' -> h - 'a' + 10
                                            in 'A'..'F' -> h - 'A' + 10
                                            else ->
                                                throw IllegalArgumentException(
                                                    "位置 ${i + k} 不是合法的十六进制数字: '$h'"
                                                )
                                        }
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar())
                                i += 4
                            }
                            // JSON 规范不允许未知转义。宽容地吞掉反斜杠只会掩盖上游生成脚本的 bug。
                            else -> throw IllegalArgumentException("位置 $i 非法的转义: '\\$esc'")
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

        /**
         * 从当前位置结构化地跳过一个 `[...]` 条目，用于单条数据损坏时的恢复。
         *
         * 必须尊重引号与转义——正文里出现 `]` 或 `\"` 是完全合法的，
         * 简单地找下一个 `]` 会在这类数据上错位。成功跳过返回 true；
         * 撞到串尾说明结构已经烂到无法定位边界，返回 false 交由上层放弃整个文件。
         */
        fun skipElement(): Boolean {
            skipWhitespace()
            if (i >= text.length || text[i] != '[') return false
            var depth = 0
            var inString = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when (c) {
                        '\\' -> i++ // 连同被转义的那个字符一起跳过
                        '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                i++
                                return true
                            }
                        }
                    }
                }
                i++
            }
            return false
        }

        /** 收尾的 `]` 之后只允许空白。截断后又被追加的文件必须报错，而不是静默接受。 */
        fun requireEnd() {
            skipWhitespace()
            require(i >= text.length) { "位置 $i 存在多余内容" }
        }

        expect('[')
        skipWhitespace()
        if (i < text.length && text[i] == ']') {
            i++
            requireEnd()
            return result
        }

        while (true) {
            // 单条失败只丢这一条，与非法类型码的容错粒度对齐——
            // 一天里的一条脏数据不该让整天变成空面板。
            val elementStart = i
            try {
                expect('[')
                val year = readInt()
                expect(',')
                val body = readString()
                expect(',')
                val code = readString()
                expect(']')

                Kind.fromCode(code)?.let { result.add(HistoryEntry(year, body, it)) }
            } catch (e: Exception) {
                i = elementStart
                if (!skipElement()) {
                    throw IllegalArgumentException("位置 $elementStart 起的条目损坏且无法跳过", e)
                }
                logWarn("跳过位置 $elementStart 起的损坏条目", e)
            }

            skipWhitespace()
            require(i < text.length) { "数组未闭合" }
            when (text[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    requireEnd()
                    return result
                }
                else -> throw IllegalArgumentException("位置 $i 期望 ',' 或 ']'")
            }
        }
    }
}
