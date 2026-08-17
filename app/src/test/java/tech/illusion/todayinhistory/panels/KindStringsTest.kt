package tech.illusion.todayinhistory.panels

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型标签**文案**的测试 —— 补 `KindMappingTest` 的盲区。
 *
 * `KindMappingTest` 的断言全部只作用于**资源 id**：
 * `assertEquals(R.string.kind_death, labelOf(Kind.DEATH))` 两侧引用同一个符号，
 * 对**内容**而言是恒真式。把 `<string name="kind_death">` 的值改成「出生」，
 * 那 6 条会全绿、编译零警告，而每一条逝世记录都显示「出生」——
 * 正是 `KindMappingTest` KDoc 第一段描述的那个失败模式，只是下沉了一层。
 *
 * 所以这里直接解析 `res/values/strings.xml` 断言文案本身。纯 JVM XML 解析，
 * 不需要 Robolectric。
 *
 * 路径解析沿用 `RealAssetIntegrityTest` 的双候选写法：Gradle 单测的 CWD 是
 * `<repo>/app`，但从仓库根跑时是 `<repo>`。
 *
 * 注意 `app/build.gradle.kts` 已把 `src/main/assets` 声明为测试输入，但**没有**
 * 声明 `src/main/res`。改 strings.xml 会改变 `processDebugResources` 的输出、
 * 进而让编译产物变化，所以单测不会被误判为 UP-TO-DATE —— 变异自证已确认这一点。
 */
class KindStringsTest {

    private fun stringsXml(): File {
        val cwd = File("").absoluteFile
        val candidates =
            listOf(
                File(cwd, "src/main/res/values/strings.xml"), // CWD = <repo>/app
                File(cwd, "app/src/main/res/values/strings.xml"), // CWD = <repo>
            )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 strings.xml，CWD=$cwd")
    }

    private fun strings(): Map<String, String> {
        val doc =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml())
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i)
                val name = el.attributes.getNamedItem("name")?.nodeValue ?: continue
                put(name, el.textContent.trim())
            }
        }
    }

    @Test
    fun `三个类型标签的文案两两不等`() {
        val s = strings()
        val labels = listOf("kind_event", "kind_birth", "kind_death").map { key ->
            requireNotNull(s[key]) { "strings.xml 缺少 $key" }
        }
        assertEquals("三个类型标签的文案必须互不相同", 3, labels.toSet().size)
    }

    @Test
    fun `每个类型标签的文案含预期字样`() {
        val s = strings()
        // 「大事记」「出生」「逝世」是产品文案；改写法可以，但不能改成别的类型的含义。
        assertEquals("大事记", s["kind_event"])
        assertEquals("出生", s["kind_birth"])
        assertEquals("逝世", s["kind_death"])
    }

    @Test
    fun `逝世标签不得含出生字样`() {
        val s = strings()
        val death = requireNotNull(s["kind_death"])
        assertTrue(
            "kind_death 的文案是「$death」，含「出生」说明映射或文案写反了",
            !death.contains("出生"),
        )
    }

    @Test
    fun `日期与空态文案存在且带正确的格式化占位符`() {
        val s = strings()
        assertNotNull("缺少 date_format", s["date_format"])
        assertNotNull("缺少 empty_day", s["empty_day"])
        val fmt = requireNotNull(s["date_format"])
        // DatePanel 按 (month, day) 顺序传参，占位符必须是带下标的 %1$d / %2$d。
        assertTrue("date_format 缺少 %1\$d：$fmt", fmt.contains("%1\$d"))
        assertTrue("date_format 缺少 %2\$d：$fmt", fmt.contains("%2\$d"))
    }
}
