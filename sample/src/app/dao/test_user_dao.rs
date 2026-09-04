use std::sync::Arc;

use hirust_mapper::{dao, Result, SqlSessionFactory};

use crate::app::models::test_user::TestUser;

pub struct TestUserDao {
    __hm_factory: Arc<SqlSessionFactory>,
}

impl TestUserDao {
    #[allow(dead_code)]
    pub fn new(factory: SqlSessionFactory) -> Self {
        Self {
            __hm_factory: Arc::new(factory),
        }
    }

    pub fn from_arc(factory: Arc<SqlSessionFactory>) -> Self {
        Self { __hm_factory: factory }
    }

    #[allow(dead_code)]
    pub fn factory(&self) -> &Arc<SqlSessionFactory> {
        &self.__hm_factory
    }
}

#[dao(namespace = "crate::app::dao::test_user_dao", xml = "resources/mapper/test_user.xml")]
impl TestUserDao {
    #[mapper_query]
    pub async fn get_all(&self) -> Result<Vec<TestUser>> {}

    #[mapper_query]
    pub async fn get_by_id(&self, test_user_id: i64) -> Result<Option<TestUser>> {}

    #[mapper_query(kind = "insert")]
    pub async fn create(&self, p: &TestUser) -> Result<i64> {}

    #[mapper_query(kind = "update")]
    pub async fn update(&self, p: &TestUser) -> Result<u64> {}

    #[mapper_query(kind = "delete")]
    pub async fn remove(&self, test_user_id: i64) -> Result<u64> {}
}
