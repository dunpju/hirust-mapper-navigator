package com.hirust.mapper.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateXmlStatementTest {

    // ------------------------------------------------------------------
    // 解析器:fn 参数
    // ------------------------------------------------------------------

    @Test
    fun `fn params parsed with self excluded`() {
        val content = """
            #[dao(namespace = "crate::a::t_dao")]
            impl TDao {
                #[mapper_query]
                pub async fn get_by_id(&self, id: u64) {}
            }
        """.trimIndent()
        val m = RustSourceParser.parse(content)[0].methods[0]
        assertEquals(listOf(FnParam("id", "u64")), m.params)
        assertTrue("签名闭括号应捕获到", m.sigEndOffset > 0)
    }

    @Test
    fun `fn params with references options and generics`() {
        val content = """
            #[dao(namespace = "crate::a::t_dao")]
            impl TDao {
                #[mapper_insert]
                pub async fn create(&self, p: &PrivilegeProject, tag: Option<String>, ids: Vec<u64>) {}
            }
        """.trimIndent()
        val params = RustSourceParser.parse(content)[0].methods[0].params
        assertEquals(3, params.size)
        assertEquals("p", params[0].name)
        assertEquals("&PrivilegeProject", params[0].typeText)
        assertEquals("Option<String>", params[1].typeText)
        assertEquals("Vec<u64>", params[2].typeText)
    }

    @Test
    fun `no params yields empty list`() {
        val content = """
            #[dao(namespace = "crate::a::t_dao")]
            impl TDao {
                #[mapper_query]
                pub async fn list_all(&self) {}
            }
        """.trimIndent()
        assertEquals(0, RustSourceParser.parse(content)[0].methods[0].params.size)
    }

    // ------------------------------------------------------------------
    // 解析器:struct 字段
    // ------------------------------------------------------------------

    @Test
    fun `struct fields parsed with pub modifiers`() {
        val content = """
            pub struct PrivilegeProject {
                pub id: u64,
                pub(crate) name: String,
                #[serde(skip)]
                cached: bool,
                remark: Option<String>,
            }
        """.trimIndent()
        val types = RustSourceParser.parseStructTypes(content)
        assertEquals(1, types.size)
        val fields = types[0].fields
        // 带属性的 cached 行:属性行被跳过,字段本身仍解析
        assertEquals(listOf("id", "name", "cached", "remark"), fields.map { it.name })
        assertEquals("u64", fields[0].typeText)
        assertEquals("bool", fields[2].typeText)
        assertEquals("Option<String>", fields[3].typeText)
    }

    @Test
    fun `tuple and unit structs have no fields`() {
        val types = RustSourceParser.parseStructTypes("struct A(u64); struct B;")
        assertEquals(2, types.size)
        assertTrue(types.all { it.fields.isEmpty() })
    }

    // ------------------------------------------------------------------
    // SQL 骨架生成
    // ------------------------------------------------------------------

    @Test
    fun `select with where from params`() {
        val sql = SqlSkeletonBuilder.build(
            "select", "get_by_id",
            listOf(FnParam("id", "u64"), FnParam("status", "i32")),
            null, "privilege_project"
        )
        assertEquals("SELECT * FROM privilege_project WHERE id = #{id} AND status = #{status}", sql)
    }

    @Test
    fun `select count from fn name`() {
        val sql = SqlSkeletonBuilder.build("select", "count_all", emptyList(), null, "t")
        assertEquals("SELECT COUNT(*) FROM t", sql)
    }

    @Test
    fun `select all without params`() {
        val sql = SqlSkeletonBuilder.build("select", "list_all", emptyList(), null, "t")
        assertEquals("SELECT * FROM t", sql)
    }

    @Test
    fun `delete with where`() {
        val sql = SqlSkeletonBuilder.build("delete", "remove", listOf(FnParam("id", "u64")), null, "t")
        assertEquals("DELETE FROM t WHERE id = #{id}", sql)
    }

    @Test
    fun `insert uses struct fields as columns`() {
        val fields = listOf(
            StructField("id", "u64"), StructField("name", "String")
        )
        val sql = SqlSkeletonBuilder.build("insert", "create", emptyList(), fields, "t")
        assertEquals("INSERT INTO t (id, name)\nVALUES (#{id}, #{name})", sql)
    }

    @Test
    fun `insert without struct falls back to todo`() {
        val sql = SqlSkeletonBuilder.build("insert", "create", emptyList(), null, "t")
        assertTrue(sql.startsWith("INSERT INTO t"))
        assertTrue(sql.contains("TODO"))
    }

    @Test
    fun `update sets non-id fields where id`() {
        val fields = listOf(
            StructField("id", "u64"), StructField("name", "String"), StructField("status", "i32")
        )
        val sql = SqlSkeletonBuilder.build(
            "update", "update", listOf(FnParam("id", "u64")), fields, "t"
        )
        assertEquals("UPDATE t SET name = #{name}, status = #{status} WHERE id = #{id}", sql)
    }
}
