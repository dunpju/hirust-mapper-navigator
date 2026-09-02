//! 手动验证样例 DAO。
//!
//! 验证点(Rust → XML):
//! - Ctrl+Click namespace 字符串 / impl 行 gutter 图标 → privilege_project.xml 的 <mapper>
//! - Ctrl+Click id 字面量("list")/ fn 行 gutter 图标 → 对应 <select>/<insert> 语句
//! - Ctrl+Click 宏名(mapper_query 等)→ hirust-mapper-macros 源码(需 path 依赖)

#[dao(namespace = "crate::app::dao::privilege_project_dao")]
impl PrivilegeProjectDao {
    /// 无 id 参数 → XML id 缺省为函数名 get_all
    #[mapper_query]
    pub async fn get_all(&self) -> Vec<PrivilegeProject> {
        todo!()
    }

    /// 显式 id = "list" → XML <select id="list">
    #[mapper_query(id = "list")]
    pub async fn list_data(&self) -> Vec<PrivilegeProject> {
        todo!()
    }

    #[mapper_insert]
    pub async fn create(&self, p: &PrivilegeProject) -> u64 {
        todo!()
    }

    #[mapper_update(id = "update_by_id")]
    pub async fn update(&self, p: &PrivilegeProject) -> u64 {
        todo!()
    }

    #[mapper_delete]
    pub async fn remove(&self, id: u64) -> u64 {
        todo!()
    }

    #[mapper_delete]
    pub async fn remove1(&self, id: u64) -> u64 {
        todo!()
    }
}

pub struct PrivilegeProject {
    pub id: u64,
    pub name: String,
}
