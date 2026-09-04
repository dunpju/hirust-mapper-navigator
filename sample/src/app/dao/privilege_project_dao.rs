use std::sync::Arc;
use hirust_mapper::{dao, Result};
use hirust_mapper_runtime::SqlSessionFactory;
use crate::app::models::privilege_project::PrivilegeProject;

pub struct PrivilegeProjectDao {
    __hm_factory: Arc<SqlSessionFactory>,
}

#[dao(namespace = "crate::app::dao::privilege_project_dao")]
impl PrivilegeProjectDao {
    #[mapper_query]
    pub async fn get_all(&self) -> Result<Vec<PrivilegeProject>> {}

    #[mapper_query(id = "list")]
    pub async fn list_data(&self) -> Result<Vec<PrivilegeProject>> {}

    #[mapper_query(kind = "insert")]
    pub async fn create(&self, p: &PrivilegeProject) -> Result<()> {}

    #[mapper_query(id = "update_by_id", kind = "update")]
    pub async fn update(&self, p: &PrivilegeProject) -> Result<()> {}

    #[mapper_query(kind = "delete")]
    pub async fn remove(&self, id: u64) -> Result<()> {}

    #[mapper_query]
    pub async fn get_by_id(&self, id: u64) -> Result<Option<PrivilegeProject>> {}
}
