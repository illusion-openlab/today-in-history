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
