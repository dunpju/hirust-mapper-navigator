package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Namespace 字符串到 XML 文件路径的解析工具。
 *
 * 核心映射逻辑:
 *   "crate::app::dao::privilege_project_dao"
 *     → 提取末段模块名: "privilege_project_dao"
 *     → 去除后缀 (_dao): "privilege_project"
 *     → 在 XML 索引中查找 stem="privilege_project" 的文件
 *     → 返回 "resources/mapper/privilege_project.xml"
 */
object NamespacePathResolver {

    private val log = Logger.getInstance(NamespacePathResolver::class.java)

    /** 需要去除的模块名后缀 */
    private val SUFFIXES = listOf(
        "_dao", "_service", "_repo", "_repository",
        "_mapper", "_accessor"
    )

    /**
     * 将 namespace 字符串解析为对应的 XML 文件
     *
     * @param project 当前项目
     * @param namespace 完整的 namespace 路径，如 "crate::app::dao::privilege_project_dao"
     * @return 匹配的 XML VirtualFile，未找到返回 null
     */
    fun resolve(project: Project, namespace: String): VirtualFile? {
        val index = XmlNamespaceIndex.getInstance(project)

        // 策略1: 精确匹配 namespace（XML 的 <mapper namespace="..."> 必须与 namespace 参数一致）
        index.getXmlFileByNamespace(namespace)?.let {
            log.debug("[hirust-mapper-navigator] Exact namespace match: $namespace → ${it.path}")
            return it
        }

        // 策略2: stem 匹配（去除后缀后的模块名）
        val lastSegment = namespace.substringAfterLast("::")
        if (lastSegment.isEmpty()) return null

        // 尝试去除各种后缀
        for (suffix in SUFFIXES) {
            if (lastSegment.endsWith(suffix) && lastSegment.length > suffix.length) {
                val stem = lastSegment.removeSuffix(suffix)
                index.getXmlFileByStem(stem)?.let {
                    log.debug("[hirust-mapper-navigator] Stem match: $namespace → stem=$stem → ${it.path}")
                    return it
                }
            }
        }

        // 策略3: 用完整模块名查找（可能 XML 文件名就是模块名）
        index.getXmlFileByStem(lastSegment)?.let {
            log.debug("[hirust-mapper-navigator] Full module name match: $namespace → ${it.path}")
            return it
        }

        log.warn("[hirust-mapper-navigator] No XML found for namespace: $namespace")
        return null
    }

    /**
     * 从 namespace 中提取最后一段模块名
     *
     * "crate::app::dao::privilege_project_dao" → "privilege_project_dao"
     */
    fun extractLastSegment(namespace: String): String {
        return namespace.substringAfterLast("::")
    }

    /**
     * 去除模块名的后缀部分
     *
     * "privilege_project_dao" → "privilege_project"
     * "user_service"          → "user"
     */
    fun extractStem(moduleName: String): String {
        for (suffix in SUFFIXES) {
            if (moduleName.endsWith(suffix) && moduleName.length > suffix.length) {
                return moduleName.removeSuffix(suffix)
            }
        }
        return moduleName
    }

    /**
     * 检查是否是有效的 namespace 格式
     */
    fun isValidNamespace(namespace: String): Boolean {
        if (namespace.isBlank()) return false
        if (!namespace.contains("::")) return false
        return namespace.split("::").all { it.isNotBlank() }
    }
}
