package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 启动预热(P1):项目打开后在后台线程执行一次协调扫描,
 * 使用户首次 Ctrl+Click / 行标记计算时索引已就绪,避免 EDT 上的全项目 IO。
 */
class MapperWarmUpStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val coordinator = MapperScanCoordinator.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!coordinator.isReady) {
                    coordinator.rebuildAll()
                }
            } catch (_: Exception) {
                // 预热失败不影响使用;首次交互时 ensureInitialized 会同步兜底
            }
        }
    }
}
