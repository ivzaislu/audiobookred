package com.example

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Database migration tests must start before AbredApplication opens the current
 * Room database. Use a plain Application in the instrumentation process so the
 * test can create an old on-disk database first and then exercise production
 * AbredDatabase.get().
 */
class MigrationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
