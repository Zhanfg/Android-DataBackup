package com.xayah.databackup.database.entity

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSystemClassificationTest {
    private fun app(flags: Int = 0, systemTrusted: Boolean = false) = App(
        packageName = "example.package",
        userId = 0,
        info = Info(flags = flags, isSystemTrusted = systemTrusted),
        option = Option(),
        storage = Storage(),
    )

    @Test
    fun updatedSystemAppIsClassifiedAsSystem() {
        assertTrue(app(flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP).isSystemApp)
    }

    @Test
    fun oemSignedDataAppIsClassifiedAsSystem() {
        assertTrue(app(systemTrusted = true).isSystemApp)
    }

    @Test
    fun ordinaryUserAppRemainsUserApp() {
        assertFalse(app().isSystemApp)
    }
}
