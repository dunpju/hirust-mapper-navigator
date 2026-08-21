package com.hirust.mapper.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RustSourceParserTest {

    private val sample = """
        use hirust_mapper::dao;

        #[dao(namespace = "crate::app::dao::privilege_project_dao")]
        impl PrivilegeProjectDao {
            #[mapper_query]
            pub async fn get_all(&self) -> Result<Vec<Project>, Error> { todo!() }

            #[mapper_query(id = "list")]
            pub async fn list_data(&self) -> Result<Vec<Project>, Error> { todo!() }

            #[mapper_insert]
            pub async fn create(&self, p: &Project) -> Result<u64, Error> { todo!() }

            #[mapper_update(id = "update_by_id")]
            pub async fn update(&self, p: &Project) -> Result<u64, Error> { todo!() }

            #[mapper_delete]
            pub async fn remove(&self, id: u64) -> Result<u64, Error> { todo!() }
        }
    """.trimIndent()

    @Test
    fun `parse basic dao with namespace`() {
        val daos = RustSourceParser.parse(sample)
        assertEquals(1, daos.size)
        val dao = daos[0]
        assertEquals("crate::app::dao::privilege_project_dao", dao.namespace)
        assertEquals("PrivilegeProjectDao", dao.implName)
        assertTrue(dao.implOffset > dao.attrOffset)
    }

    @Test
    fun `id defaults to fn name when param absent`() {
        val daos = RustSourceParser.parse(sample)
        val m = daos[0].methods.first { it.fnName == "get_all" }
        assertEquals("get_all", m.id)
        assertEquals(-1, m.idLiteralOffset)
        assertEquals("select", m.stmtTag)
    }

    @Test
    fun `id param overrides fn name`() {
        val daos = RustSourceParser.parse(sample)
        val m = daos[0].methods.first { it.fnName == "list_data" }
        assertEquals("list", m.id)
        assertTrue(m.idLiteralOffset > 0)
        assertEquals("select", m.stmtTag)
    }

    @Test
    fun `stmt tag mapping for crud macros`() {
        val daos = RustSourceParser.parse(sample)
        val methods = daos[0].methods.associateBy { it.fnName }
        assertEquals("select", methods["get_all"]!!.stmtTag)
        assertEquals("insert", methods["create"]!!.stmtTag)
        assertEquals("update", methods["update_by_id"]!!.stmtTag)
        assertEquals("delete", methods["remove"]!!.stmtTag)
    }

    @Test
    fun `unknown mapper macro maps to select`() {
        val content = """
            #[dao(namespace = "crate::a::b_dao")]
            impl BDao {
                #[mapper_exec]
                fn run(&self) {}
            }
        """.trimIndent()
        val m = RustSourceParser.parse(content)[0].methods[0]
        assertEquals("select", m.stmtTag)
        assertEquals("run", m.id)
    }

    @Test
    fun `offsets point into source text`() {
        val daos = RustSourceParser.parse(sample)
        val dao = daos[0]
        // attrOffset 指向 '#['
        assertTrue(sample.substring(dao.attrOffset, dao.attrOffset + 2) == "#[")
        // nsLiteralOffset 指向引号内第一个字符
        assertEquals('c', sample[dao.nsLiteralOffset])
        assertEquals('"', sample[dao.nsLiteralOffset - 1])
        // 宏名偏移
        assertEquals("dao", sample.substring(dao.attrNameOffset, dao.attrNameOffset + 3))
        // impl 关键字
        assertTrue(sample.substring(dao.implOffset, dao.implOffset + 4) == "impl")

        val m = dao.methods.first { it.fnName == "list_data" }
        assertTrue(sample.substring(m.macroOffset, m.macroOffset + 2) == "#[")
        assertEquals("mapper_query", sample.substring(m.macroNameOffset, m.macroNameOffset + "mapper_query".length))
        assertEquals("fn", sample.substring(m.fnOffset - 3, m.fnOffset))
        assertEquals("list_data", sample.substring(m.fnOffset, m.fnOffset + "list_data".length))
        assertEquals('l', sample[m.idLiteralOffset])
        assertEquals("list", sample.substring(m.idLiteralOffset, m.idLiteralOffset + 4))
    }

    @Test
    fun `multiple daos and method ownership`() {
        val content = """
            #[dao(namespace = "crate::a::one_dao")]
            impl OneDao {
                #[mapper_query]
                fn a1(&self) {}
            }

            #[dao(namespace = "crate::a::two_dao")]
            impl TwoDao {
                #[mapper_query(id = "b")]
                fn a2(&self) {}
            }
        """.trimIndent()
        val daos = RustSourceParser.parse(content)
        assertEquals(2, daos.size)
        assertEquals("a1", daos[0].methods[0].id)
        assertEquals("b", daos[1].methods[0].id)
        assertEquals("OneDao", daos[0].implName)
        assertEquals("TwoDao", daos[1].implName)
    }

    @Test
    fun `dao without namespace is skipped together with its methods`() {
        val content = """
            #[dao]
            impl NoNsDao {
                #[mapper_query]
                fn lost(&self) {}
            }

            #[dao(namespace = "crate::a::ok_dao")]
            impl OkDao {
                #[mapper_query]
                fn kept(&self) {}
            }
        """.trimIndent()
        val daos = RustSourceParser.parse(content)
        assertEquals(1, daos.size)
        assertEquals("kept", daos[0].methods[0].id)
    }

    @Test
    fun `methods without preceding dao are ignored`() {
        val content = """
            #[mapper_query]
            fn orphan(&self) {}

            #[dao(namespace = "crate::a::d_dao")]
            impl DDao {
                #[mapper_query]
                fn kept(&self) {}
            }
        """.trimIndent()
        val daos = RustSourceParser.parse(content)
        assertEquals(1, daos.size)
        assertEquals(listOf("kept"), daos[0].methods.map { it.fnName })
    }

    @Test
    fun `nested parens and strings in dao params`() {
        val content = """
            #[dao(namespace = "crate::a::x_dao", other = foo(1, 2), label = "a)b")]
            impl XDao {
                #[mapper_query]
                fn q(&self) {}
            }
        """.trimIndent()
        val dao = RustSourceParser.parse(content)[0]
        assertEquals("crate::a::x_dao", dao.namespace)
        assertEquals(1, dao.methods.size)
    }

    @Test
    fun `non dao source returns empty`() {
        assertEquals(0, RustSourceParser.parse("fn main() { println!(\"hi\"); }").size)
        assertEquals(0, RustSourceParser.parse("").size)
    }

    @Test
    fun `inner attributes and path attributes are tolerated`() {
        val content = """
            #![allow(dead_code)]

            #[derive(Debug, Clone)]
            pub struct Foo;

            #[tracing::instrument]
            #[dao(namespace = "crate::a::y_dao")]
            impl YDao {
                #[serde(skip)]
                #[mapper_query]
                pub(crate) async unsafe fn deep(&self) {}
            }
        """.trimIndent()
        val daos = RustSourceParser.parse(content)
        assertEquals(1, daos.size)
        assertEquals(1, daos[0].methods.size)
        assertEquals("deep", daos[0].methods[0].fnName)
    }

    @Test
    fun `comments between attr and fn are skipped`() {
        val content = """
            #[dao(namespace = "crate::a::z_dao")]
            impl ZDao {
                /// doc comment
                // line comment
                /* block comment */
                #[mapper_query] // trailing
                fn commented(&self) {}
            }
        """.trimIndent()
        val m = RustSourceParser.parse(content)[0].methods[0]
        assertEquals("commented", m.fnName)
    }

    @Test
    fun `generic impl name extracted`() {
        val content = """
            #[dao(namespace = "crate::a::g_dao")]
            impl<'a> GenericDao<'a> {
                #[mapper_query]
                fn q(&self) {}
            }
        """.trimIndent()
        assertEquals("GenericDao", RustSourceParser.parse(content)[0].implName)
    }
}
