package com.hirust.mapper.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdlScaffoldingTest {

    private val ddl = """
        CREATE TABLE `privilege_user` (
          `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
          `name` VARCHAR(64) NOT NULL,
          `balance` DECIMAL(10,2) DEFAULT NULL,
          `age` INT,
          `is_admin` TINYINT(1) NOT NULL DEFAULT 0,
          `data` BLOB,
          `type` VARCHAR(20) NOT NULL,
          `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (`id`)
        ) ENGINE=InnoDB;
    """.trimIndent()

    @Test
    fun `parse create table with pk constraint`() {
        val tables = DdlScaffolding.parseCreateTables(ddl)
        assertEquals(1, tables.size)
        val t = tables[0]
        assertEquals("privilege_user", t.name)
        assertEquals(8, t.columns.size)
        val id = t.columns.first { it.name == "id" }
        assertTrue(id.isPk)
        assertTrue(!id.nullable)
        val balance = t.columns.first { it.name == "balance" }
        assertTrue(balance.nullable)
        val age = t.columns.first { it.name == "age" }
        assertTrue(age.nullable)
    }

    @Test
    fun `multiple tables parsed`() {
        val two = "$ddl\nCREATE TABLE order_item (id BIGINT PRIMARY KEY, qty INT NOT NULL);"
        assertEquals(2, DdlScaffolding.parseCreateTables(two).size)
    }

    @Test
    fun `sql to rust type mapping`() {
        assertEquals("i64", DdlScaffolding.rustTypeFor("BIGINT", false))
        assertEquals("i32", DdlScaffolding.rustTypeFor("INT", false))
        assertEquals("Option<i32>", DdlScaffolding.rustTypeFor("INT", true))
        assertEquals("bool", DdlScaffolding.rustTypeFor("TINYINT", false))
        assertEquals("String", DdlScaffolding.rustTypeFor("VARCHAR", false))
        assertEquals("Option<String>", DdlScaffolding.rustTypeFor("DATETIME", true))
        assertEquals("Vec<u8>", DdlScaffolding.rustTypeFor("BLOB", false))
        assertEquals("f64", DdlScaffolding.rustTypeFor("DOUBLE", false))
    }

    @Test
    fun `struct name pascal case and keyword fields`() {
        assertEquals("PrivilegeUser", DdlScaffolding.structName("privilege_user"))
        assertEquals("r#type", DdlScaffolding.fieldName("type"))
        assertEquals("name", DdlScaffolding.fieldName("name"))
    }

    @Test
    fun `struct code generated`() {
        val code = DdlScaffolding.structCode(DdlScaffolding.parseCreateTables(ddl)[0])
        assertTrue(code.contains("pub struct PrivilegeUser {"))
        assertTrue(code.contains("pub id: i64,"))
        assertTrue(code.contains("pub balance: Option<f64>,"))
        assertTrue(code.contains("pub r#type: String,"))
        assertTrue(code.contains("pub data: Option<Vec<u8>>,"))
    }

    @Test
    fun `dao code generated with namespace and crud`() {
        val code = DdlScaffolding.daoCode(
            DdlScaffolding.parseCreateTables(ddl)[0],
            "crate::app::dao::privilege_user_dao",
            "crate::app::models"
        )
        assertTrue(code.contains("#[dao(namespace = \"crate::app::dao::privilege_user_dao\")]"))
        assertTrue(code.contains("use crate::app::models::PrivilegeUser;"))
        assertTrue(code.contains("#[mapper_query]"))
        assertTrue(code.contains("pub async fn get_by_id(&self, id: i64) -> PrivilegeUser {}"))
        assertTrue(code.contains("#[mapper_insert]"))
        assertTrue(code.contains("#[mapper_delete]"))
    }

    @Test
    fun `mapper xml generated with full crud`() {
        val xml = DdlScaffolding.mapperXml(
            DdlScaffolding.parseCreateTables(ddl)[0],
            "crate::app::dao::privilege_user_dao"
        )
        assertTrue(xml.contains("""<mapper namespace="crate::app::dao::privilege_user_dao">"""))
        assertTrue(xml.contains("""resultType="PrivilegeUser""""))
        assertTrue(xml.contains("SELECT `id`, `name`, `balance`, `age`, `is_admin`, `data`, `type`, `created_at` FROM privilege_user"))
        assertTrue(xml.contains("INSERT INTO privilege_user"))
        assertTrue(xml.contains("UPDATE privilege_user SET `name` = #{name}"))
        assertTrue(xml.contains("DELETE FROM privilege_user WHERE `id` = #{id}"))
    }
}
