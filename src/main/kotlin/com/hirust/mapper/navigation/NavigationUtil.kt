package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * 跳转工具:打开文件并定位到指定偏移。
 *
 * 引用解析(resolve)返回目标元素;行标记导航直接调用 [openAt]。
 */
object NavigationUtil {

    /** 打开文件并把光标定位到 offset(EDT 上执行,滚动到可见) */
    fun openAt(project: Project, file: VirtualFile, offset: Int) {
        val runnable = Runnable {
            if (project.isDisposed || !file.isValid) return@Runnable
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file, offset.coerceAtLeast(0)), true)
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) runnable.run() else app.invokeLater(runnable, project.disposed)
    }

    /** 获取文件中 offset 处的 PSI 元素(引用解析的返回目标) */
    fun findElement(file: VirtualFile, project: Project, offset: Int): PsiElement? {
        if (!file.isValid) return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        return psiFile.findElementAt(offset.coerceIn(0, (psiFile.textLength - 1).coerceAtLeast(0)))
    }
}
