package com.hirust.mapper.navigation

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 插件图标。参照 MybatisX 的双向跳转小鸟图标风格:
 * - TO_XML:Rust → XML 方向(绿色小鸟飞向橙色文档)
 * - TO_RUST:XML → Rust 方向(橙色小鸟飞向蓝色齿轮)
 */
object Icons {
    val TO_XML: Icon = IconLoader.getIcon("/icons/toXml.svg", Icons::class.java)
    val TO_RUST: Icon = IconLoader.getIcon("/icons/toRust.svg", Icons::class.java)
}
