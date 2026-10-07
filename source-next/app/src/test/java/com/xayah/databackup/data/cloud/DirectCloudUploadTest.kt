package com.xayah.databackup.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCloudUploadTest {
    @Test
    fun uploadSessionDoesNotPrintBearerUrl() {
        val secretUrl = "https://provider.invalid/upload?token=sensitive"
        val session = DirectUploadSession(secretUrl, "2099-01-01T00:00:00Z")
        assertFalse(session.toString().contains(secretUrl))
        assertTrue(session.toString().contains("<redacted>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkCannotExceedDeclaredSize() {
        validateUploadChunk(offset = 8, totalBytes = 10, bytes = ByteArray(3))
    }
}
