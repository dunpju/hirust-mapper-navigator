use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrivilegeProject {
    pub id: u64,
    pub name: String,
}
