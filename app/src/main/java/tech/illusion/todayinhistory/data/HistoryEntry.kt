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
