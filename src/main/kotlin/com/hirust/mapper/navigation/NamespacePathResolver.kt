package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

object NamespacePathResolver {

    private val log = Logger.getInstance(NamespacePathResolver::class.java)

    private val SUFFIXES = listOf(
        "_dao", "_service", "_repo", "_repository",
        "_mapper", "_accessor"
    )

    fun resolve(project: Project, namespace: String): VirtualFile? {
        val index = XmlNamespaceIndex.getInstance(project)

        // 策略1: 精确匹配 namespace
        val exactMatch = index.getXmlFileByNamespace(namespace)
        if (exactMatch != null) {
            log.debug("[hirust-mapper-navigator] Exact namespace match: $namespace")
            return exactMatch
        }

        // 策略2: stem 匹配（去除后缀后的模块名）
        val lastSegment = namespace.substringAfterLast("::")
        if (lastSegment.isEmpty()) return null

        for (suffix in SUFFIXES) {
            if (lastSegment.endsWith(suffix) && lastSegment.length > suffix.length) {
                val stem = lastSegment.removeSuffix(suffix)
                val stemMatch = index.getXmlFileByStem(stem)
                if (stemMatch != null) {
                    log.debug("[hirust-mapper-navigator] Stem match: $namespace -> stem=$stem")
                    return stemMatch
                }
            }
        }

        // 策略3: 用完整模块名查找
        val moduleMatch = index.getXmlFileByStem(lastSegment)
        if (moduleMatch != null) {
            log.debug("[hirust-mapper-navigator] Full module name match: $namespace")
            return moduleMatch
        }

        log.warn("[hirust-mapper-navigator] No XML found for namespace: $namespace")
        return null
    }

    fun extractLastSegment(namespace: String): String =
        namespace.substringAfterLast("::")

    fun extractStem(moduleName: String): String {
        for (suffix in SUFFIXES) {
            if (moduleName.endsWith(suffix) && moduleName.length > suffix.length) {
                return moduleName.removeSuffix(suffix)
            }
        }
        return moduleName
    }

    /** 接受 `::` 分隔(crate::module::xxx_dao)与 `.` 分隔(dao.subject)两种 namespace 风格 */
    fun isValidNamespace(namespace: String): Boolean =
        namespace.isNotBlank() && (namespace.contains("::") || namespace.contains('.'))
}
