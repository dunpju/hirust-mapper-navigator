#[dao(namespace = "crate::app::dao::test_user_dao")]
impl TestUserDao {
    #[mapper_query]
    pub async fn get_all(&self) -> Vec<TestUser> {}

    #[mapper_query]
    pub async fn get_by_id(&self, id: i64) -> TestUser {}

    #[mapper_insert]
    pub async fn create(&self, p: &TestUser) -> u64 {}

    #[mapper_update]
    pub async fn update(&self, p: &TestUser) -> u64 {}

    #[mapper_delete]
    pub async fn remove(&self, id: i64) -> u64 {}
}
