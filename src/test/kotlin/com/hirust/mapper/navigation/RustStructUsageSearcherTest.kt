package com.hirust.mapper.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RustStructUsageSearcherTest {

    private val rustSample = """
        use crate::app::models::count::CountRow;
        use crate::app::models::CountRow as Row;

        pub struct CountRow {
            pub total: i64,
        }

        pub struct OtherModel {
            pub rows: Vec<CountRow>,
        }

        pub async fn count_all(&self) -> Result<Vec<CountRow>, Error> {}

        fn helper() -> CountRow {}

        fn local_shadowed() {
            let rows: Vec<CountRow> = vec![];
            let x = CountRow { total: 1 };
        }

        // CountRow in line comment should be ignored
        /// CountRow in doc comment should be ignored
        fn ignored() {}
    """.trimIndent()

    @Test
    fun `rust usages cover five categories and skip definitions and comments`() {
        val hits = RustStructUsageSearcher.rustUsageOffsets(rustSample, "CountRow")
        val lines = hits.map { it.second }
        // 定义处(第 4 行,0 基 3)不应出现
        assertTrue("definition excluded", 3 !in lines)
        // 行注释/文档注释行不应出现
        val commentLines = rustSample.lines().mapIndexedNotNull { i, l ->
            if (l.trimStart().startsWith("//")) i else null
        }
        commentLines.forEach { assertTrue("comment line $it excluded", it !in lines) }

        val textLines = rustSample.lines()
        hits.forEach { (offset, line) ->
            // 每个命中偏移必须指向 CountRow 本身
            assertEquals("hit at line $line", "CountRow", rustSample.substring(offset, offset + "CountRow".length))
            assertTrue("line number matches offset", textLines[line].contains("CountRow"))
        }
        // use 导入 ×2、结构体字段泛型 ×1、返回值泛型 ×1、返回值类型 ×1、
        // 局部泛型 ×1、局部构造 ×1 —— 共 7 处
        assertEquals("expect 7 usages, got ${hits.size} at lines ${hits.map { it.second }}",
                7, hits.size)
    }

    @Test
    fun `rust word boundary does not match substrings`() {
        val content = "let a = CountRowX; let b = MyCountRow; let c = CountRow;"
        val hits = RustStructUsageSearcher.rustUsageOffsets(content, "CountRow")
        assertEquals(1, hits.size)
        assertTrue(content.substring(hits[0].first).startsWith("CountRow;"))
    }

    @Test
    fun `xml type attribute usages with qualified names`() {
        val xml = """
            <mapper namespace="x">
                <select id="count" resultType="CountRow">SELECT 1</select>
                <select id="count2" resultType="dto::count::CountRow">SELECT 2</select>
                <select id="other" resultType="OtherModel">SELECT 3</select>
                <select id="map" resultMap="CountRowMap">SELECT 4</select>
            </mapper>
        """.trimIndent()
        val hits = RustStructUsageSearcher.xmlUsageOffsets(xml, "CountRow")
        // resultType=CountRow、resultType=dto::...::CountRow 命中;OtherModel 不命中;
        // resultMap=CountRowMap 为【前缀子串】而非全词,不命中
        assertEquals(2, hits.size)
        hits.forEach { (offset, _) ->
            assertEquals("CountRow", xml.substring(offset, offset + "CountRow".length))
        }
        // 命中行(0 基)分别为第 1、2 行
        assertEquals(listOf(1, 2), hits.map { it.second })
    }

    @Test
    fun `empty and missing names are safe`() {
        assertTrue(RustStructUsageSearcher.rustUsageOffsets(rustSample, "").isEmpty())
        assertTrue(RustStructUsageSearcher.rustUsageOffsets("plain text", "CountRow").isEmpty())
        assertTrue(RustStructUsageSearcher.xmlUsageOffsets("<a/>", "CountRow").isEmpty())
        assertTrue(RustStructUsageSearcher.xmlUsageOffsets("", "").isEmpty())
    }
}
