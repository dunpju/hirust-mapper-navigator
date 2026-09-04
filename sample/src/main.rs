//! 手动验证样例:`.with_mapper_paths(vec![...])` 配置声明 XML 资源目录,
//! 插件由此自动发现 mapper XML 文件。
//!
//! 本样例仅用于插件导航验证,不实际运行数据库操作。

#![allow(dead_code)]
//!
//! v1.2.2:改为真实的链式调用形态 —— 模式相对【crate 根】解析
//! (运行时 `SqlSessionFactory::build(config, ".")` 以 crate 根为基准),
//! 插件从声明文件的 crate 根(向上找 Cargo.toml)与项目根两个基准依次尝试。

mod app;

fn main() {
    let _mapper_config = MapperConfig::default()
        .with_sql_log(true)
        // 插件从下面的字符串字面量提取 glob 模式
        .with_mapper_paths(vec![
            "resources/mapper/**/*.xml".to_string(),
        ]);
    println!("hello hirust-mapper sample");
}

/// 占位配置(真实类型来自 hirust_mapper crate)
struct MapperConfig {
    mapper_paths: Vec<String>,
    sql_log: bool,
}

impl MapperConfig {
    fn default() -> Self {
        Self { mapper_paths: Vec::new(), sql_log: false }
    }

    fn with_sql_log(mut self, enabled: bool) -> Self {
        self.sql_log = enabled;
        self
    }

    fn with_mapper_paths(mut self, paths: Vec<String>) -> Self {
        self.mapper_paths = paths;
        self
    }
}
