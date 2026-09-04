use std::sync::Arc;
use hirust_mapper::{dao, Result};
use hirust_mapper_runtime::SqlSessionFactory;
use crate::app::models::test_user::TestUser;

pub struct TestUserDao {
    __hm_factory: Arc<SqlSessionFactory>,
}

#[dao(namespace = "crate::app::dao::test_user_dao")]
impl TestUserDao {
    #[mapper_query]
    pub async fn get_all(&self) -> Result<Vec<TestUser>> {}

    #[mapper_query]
    pub async fn get_by_id(&self, id: i64) -> Result<Option<TestUser>> {}

    #[mapper_query(kind = "insert")]
    pub async fn create(&self, p: &TestUser) -> Result<()> {}

    #[mapper_query(kind = "update")]
    pub async fn update(&self, p: &TestUser) -> Result<()> {}

    #[mapper_query(kind = "delete")]
    pub async fn remove(&self, id: i64) -> Result<()> {}
}
