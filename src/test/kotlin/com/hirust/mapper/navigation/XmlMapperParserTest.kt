package com.hirust.mapper.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlMapperParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="crate::app::dao::privilege_project_dao">
            <select id="get_all" resultType="Project">
                SELECT * FROM privilege_project
            </select>
            <select id="list">
                SELECT * FROM privilege_project LIMIT 10
            </select>
            <insert id="create">
                INSERT INTO privilege_project (name) VALUES (#{name})
            </insert>
            <update id="update_by_id">
                UPDATE privilege_project SET name = #{name} WHERE id = #{id}
            </update>
            <delete id="remove">
                DELETE FROM privilege_project WHERE id = #{id}
            </delete>
        </mapper>
    """.trimIndent()

    @Test
    fun `parse namespace and mapper offset`() {
        val info = XmlMapperParser.parse(sample)!!
        assertEquals("crate::app::dao::privilege_project_dao", info.namespace)
        assertEquals('<', sample[info.mapperTagOffset])
        assertTrue(sample.startsWith("<mapper", info.mapperTagOffset))
    }

    @Test
    fun `parse all statements with tags and ids`() {
        val info = XmlMapperParser.parse(sample)!!
        assertEquals(5, info.statements.size)
        val byId = info.statements.associateBy { it.id }
        assertEquals("select", byId["get_all"]!!.tag)
        assertEquals("select", byId["list"]!!.tag)
        assertEquals("insert", byId["create"]!!.tag)
        assertEquals("update", byId["update_by_id"]!!.tag)
        assertEquals("delete", byId["remove"]!!.tag)
    }

    @Test
    fun `statement offsets point into source text`() {
        val info = XmlMapperParser.parse(sample)!!
        val stmt = info.statements.first { it.id == "get_all" }
        assertTrue(sample.startsWith("<select", stmt.tagOffset))
        // idAttrOffset 指向引号内第一个字符
        assertEquals('g', sample[stmt.idAttrOffset])
        assertEquals('"', sample[stmt.idAttrOffset - 1])
        assertEquals("get_all", sample.substring(stmt.idAttrOffset, stmt.idAttrOffset + "get_all".length))
        // 偏移量递增
        val offsets = info.statements.map { it.tagOffset }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `no namespace returns null`() {
        assertNull(XmlMapperParser.parse("<mapper><select id=\"a\">1</select></mapper>"))
        assertNull(XmlMapperParser.parse("<mapper namespace='single-quote-ns'></mapper>"))
        assertNull(XmlMapperParser.parse(""))
        assertNull(XmlMapperParser.parse("not xml at all"))
    }

    @Test
    fun `statement without id is skipped`() {
        val info = XmlMapperParser.parse(
            """<mapper namespace="crate::a::b_dao">
                <select>SELECT 1</select>
                <select id="ok">SELECT 2</select>
            </mapper>"""
        )!!
        assertEquals(1, info.statements.size)
        assertEquals("ok", info.statements[0].id)
    }

    @Test
    fun `self closing and multiline attributes`() {
        val content = """
            <mapper namespace="crate::a::c_dao">
                <select
                    id="multi_line"
                    resultType="X"/>
                <insert id="selfclose"></insert>
            </mapper>
        """.trimIndent()
        val info = XmlMapperParser.parse(content)!!
        assertEquals(setOf("multi_line", "selfclose"), info.statements.map { it.id }.toSet())
    }

    @Test
    fun `similar tag names are not matched`() {
        val content = """
            <mapper namespace="crate::a::d_dao">
                <selected id="nope">x</selected>
                <updating id="nope2">y</updating>
                <select id="yes">z</select>
            </mapper>
        """.trimIndent()
        val info = XmlMapperParser.parse(content)!!
        assertEquals(listOf("yes"), info.statements.map { it.id })
    }

    @Test
    fun `extractNamespace shortcut`() {
        assertEquals("crate::a::e_dao", XmlMapperParser.extractNamespace("""<mapper namespace="crate::a::e_dao"/>"""))
        assertNull(XmlMapperParser.extractNamespace("<mapper/>"))
    }

    // ------------------------------------------------------------------
    // v1.2.4:sql 可复用片段解析(include refid 跳转目标)
    // ------------------------------------------------------------------

    private val sqlSample = """
        <mapper namespace="dao.question">
            <sql id="list_where">
                <where>deleted = 0</where>
            </sql>
            <sql id="base_columns">id, name, created_at</sql>
            <select id="list_all" resultType="Question">
                SELECT <include refid="base_columns"/>
                FROM questions
                <include refid="list_where"/>
            </select>
            <select id="list_all2" resultType="Question">
                SELECT * FROM questions
                <include refid="dao.question.list_where"/>
            </select>
        </mapper>
    """.trimIndent()

    @Test
    fun `sql fragments parsed with ids`() {
        val info = XmlMapperParser.parse(sqlSample)!!
        assertEquals(setOf("list_where", "base_columns"), info.sqlFragments.map { it.id }.toSet())
        // 语句解析不受影响
        assertEquals(setOf("list_all", "list_all2"), info.statements.map { it.id }.toSet())
    }

    @Test
    fun `sql fragment offsets point into source text`() {
        val info = XmlMapperParser.parse(sqlSample)!!
        val frag = info.sqlFragments.first { it.id == "list_where" }
        assertTrue(sqlSample.startsWith("<sql", frag.tagOffset))
        // idAttrOffset 指向引号内第一个字符
        assertEquals('l', sqlSample[frag.idAttrOffset])
        assertEquals('"', sqlSample[frag.idAttrOffset - 1])
        assertEquals(
            "list_where",
            sqlSample.substring(frag.idAttrOffset, frag.idAttrOffset + "list_where".length)
        )
        // 片段偏移量递增
        val offsets = info.sqlFragments.map { it.tagOffset }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `sql fragment without id is skipped`() {
        val info = XmlMapperParser.parse(
            """<mapper namespace="dao.x">
                <sql>no id here</sql>
                <sql id="ok">has id</sql>
            </mapper>"""
        )!!
        assertEquals(listOf("ok"), info.sqlFragments.map { it.id })
    }

    @Test
    fun `sql like tag names are not matched`() {
        val info = XmlMapperParser.parse(
            """<mapper namespace="dao.y">
                <sqlite id="nope">x</sqlite>
                <sqle id="nope2">y</sqle>
                <sql id="yes">z</sql>
            </mapper>"""
        )!!
        assertEquals(listOf("yes"), info.sqlFragments.map { it.id })
    }

    @Test
    fun `sql fragments default to empty for legacy parsers`() {
        val info = XmlMapperParser.parse(sample)!!
        assertTrue(info.sqlFragments.isEmpty())
    }
}
