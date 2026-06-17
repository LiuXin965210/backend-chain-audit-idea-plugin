package com.liuxin.backendchain.silent

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class SilentTaskStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        SilentTaskRunner(project).runIfPresent()
    }
}

