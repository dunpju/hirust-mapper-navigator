use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TestUser {
    pub id: i64,
    pub name: String,
    pub age: Option<i32>,
    pub is_active: bool,
}
