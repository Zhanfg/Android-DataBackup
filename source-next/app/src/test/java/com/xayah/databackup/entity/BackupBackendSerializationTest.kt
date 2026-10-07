package com.xayah.databackup.entity

import com.squareup.moshi.Moshi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupBackendSerializationTest {
    @Test
    fun rusticPasswordIsNotSerializedOrPrinted() {
        val backend = BackupBackend.Rustic(password = "highly-sensitive-test-secret")
        val json = Moshi.Builder().build()
            .adapter(BackupBackend.Rustic::class.java)
            .toJson(backend)

        assertFalse(json.contains("highly-sensitive-test-secret"))
        assertFalse(backend.toString().contains("highly-sensitive-test-secret"))
        assertTrue(backend.toString().contains("<redacted>"))
    }
}
