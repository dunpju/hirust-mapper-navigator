/// 表 `test_user` 对应模型(由 DDL 脚手架生成)
#[derive(Debug, Clone)]
pub struct TestUser {
    /// BIGINT, 主键
    pub id: i64,
    /// VARCHAR(64)
    pub name: String,
    /// INT
    pub age: Option<i32>,
    /// TINYINT(1)
    pub is_active: bool,
}
