//! 手动验证样例:with_mapper_paths 配置声明 XML 资源目录,
//! 插件由此自动发现 mapper XML 文件。
//!
//! 注意:本样例不依赖真实 hirust-mapper crate(仅用于插件导航验证),
//! 相关类型以占位形式声明。

mod app;

fn main() {
    let _mapper_config = MapperConfig {
        mapper_paths: vec![
            // 插件从下面的字符串字面量提取 glob 模式
            "resources/mapper/**/*.xml".to_string(),
        ],
    };
    println!("hello hirust-mapper sample");
}

struct MapperConfig {
    mapper_paths: Vec<String>,
}
