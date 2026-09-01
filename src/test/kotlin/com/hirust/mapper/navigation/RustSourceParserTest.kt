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
        assertEquals("update", methods["update"]!!.stmtTag)
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
        assertEquals("fn ", sample.substring(m.fnOffset - 3, m.fnOffset))
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

    // ------------------------------------------------------------------
    // v1.2.2:kind 参数 / xml 属性 / implNameOffset / 点号 namespace
    // ------------------------------------------------------------------

    /** 目标项目(hirust-error-book)的真实代码形态 */
    private val dotNamespaceSample = """
        use hirust_mapper::{dao, Result, SqlSessionFactory};

        pub struct SubjectDao {
            __hm_factory: std::sync::Arc<SqlSessionFactory>,
        }

        #[dao(namespace = "dao.subject", xml = "mappers/SubjectMapper.xml")]
        impl SubjectDao {
            /// 全部学科
            #[mapper_query]
            pub async fn list_all(&self) -> Result<Vec<Subject>> {}

            #[mapper_query(kind = "insert")]
            pub async fn create(&self, s: &Subject) -> Result<u64> {}

            #[mapper_query(kind = "update", id = "soft_delete")]
            pub async fn delete(&self, subject_id: i64) -> Result<u64> {}
        }
    """.trimIndent()

    @Test
    fun `kind param overrides macro name mapping`() {
        val daos = RustSourceParser.parse(dotNamespaceSample)
        val methods = daos[0].methods.associateBy { it.fnName }
        // 裸 mapper_query → select
        assertEquals("select", methods["list_all"]!!.stmtTag)
        // kind 参数优先:mapper_query + kind="insert" → insert
        assertEquals("insert", methods["create"]!!.stmtTag)
        // kind 与 id 参数并存(参数顺序颠倒,目标项目真实形态)
        assertEquals("update", methods["delete"]!!.stmtTag)
        assertEquals("soft_delete", methods["delete"]!!.id)
    }

    @Test
    fun `invalid kind value falls back to macro name mapping`() {
        val content = """
            #[dao(namespace = "crate::a::k_dao")]
            impl KDao {
                #[mapper_query(kind = "exec")]
                fn q(&self) {}
            }
        """.trimIndent()
        assertEquals("select", RustSourceParser.parse(content)[0].methods[0].stmtTag)
    }

    @Test
    fun `xml attr extracted with offset into quotes`() {
        val daos = RustSourceParser.parse(dotNamespaceSample)
        val dao = daos[0]
        assertEquals("mappers/SubjectMapper.xml", dao.xmlAttr)
        // 偏移指向引号内第一个字符
        assertEquals('m', dotNamespaceSample[dao.xmlAttrOffset])
        assertEquals('"', dotNamespaceSample[dao.xmlAttrOffset - 1])
        assertEquals(
            "mappers/SubjectMapper.xml",
            dotNamespaceSample.substring(dao.xmlAttrOffset, dao.xmlAttrOffset + dao.xmlAttr.length)
        )
    }

    @Test
    fun `xml attr defaults when absent`() {
        val daos = RustSourceParser.parse(sample)
        assertEquals("", daos[0].xmlAttr)
        assertEquals(-1, daos[0].xmlAttrOffset)
    }

    @Test
    fun `impl name offset points at type name`() {
        val daos = RustSourceParser.parse(dotNamespaceSample)
        val dao = daos[0]
        assertEquals("SubjectDao", dao.implName)
        assertTrue(dao.implNameOffset > 0)
        assertEquals('S', dotNamespaceSample[dao.implNameOffset])
        assertEquals(
            "SubjectDao",
            dotNamespaceSample.substring(dao.implNameOffset, dao.implNameOffset + dao.implName.length)
        )
    }

    @Test
    fun `impl name offset is minus one when name missing`() {
        // impl 后直接 '{',无类型名
        val content = "#[dao(namespace = \"crate::a::n_dao\")]\nimpl {\n    #[mapper_query]\n    fn q(&self) {}\n}"
        val dao = RustSourceParser.parse(content)[0]
        assertEquals(-1, dao.implNameOffset)
        // implName 回退为关键字本身(保持 findDaoAt 区间语义)
        assertEquals("impl", dao.implName)
    }

    @Test
    fun `dot style namespace parsed`() {
        val daos = RustSourceParser.parse(dotNamespaceSample)
        assertEquals(1, daos.size)
        assertEquals("dao.subject", daos[0].namespace)
        assertEquals('d', dotNamespaceSample[daos[0].nsLiteralOffset])
        assertEquals(3, daos[0].methods.size)
    }

    // ------------------------------------------------------------------
    // v1.2.7:struct 类型扫描(resultType 跳转目标)
    // ------------------------------------------------------------------

    @Test
    fun `struct types scanned with name offsets`() {
        val content = """
            use serde::Deserialize;

            #[derive(Debug, Deserialize)]
            pub struct CountRow {
                pub cnt: i64,
            }

            pub struct QuestionStatus(i32);

            struct UnitLike;
        """.trimIndent()
        val types = RustSourceParser.parseStructTypes(content)
        assertEquals(listOf("CountRow", "QuestionStatus", "UnitLike"), types.map { it.name })
        // nameOffset 指向类型名首字符(前面是 "struct ")
        for (t in types) {
            assertEquals(' ', content[t.nameOffset - 1])
            assertEquals(t.name, content.substring(t.nameOffset, t.nameOffset + t.name.length))
        }
    }

    @Test
    fun `struct like words are not matched`() {
        val content = "let restructured = 1; // restruct foo\nfn f(x: RestructHolder) {}"
        assertTrue(RustSourceParser.parseStructTypes(content).isEmpty())
    }

    @Test
    fun `no structs returns empty`() {
        assertTrue(RustSourceParser.parseStructTypes("fn main() {}").isEmpty())
        assertTrue(RustSourceParser.parseStructTypes("").isEmpty())
    }
}
