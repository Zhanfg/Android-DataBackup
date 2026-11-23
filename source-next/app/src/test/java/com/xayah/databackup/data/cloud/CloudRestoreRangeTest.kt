package com.xayah.databackup.data.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CancellationException

class CloudRestoreRangeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun verifiedSnapshotStreamsExactOffsetsIntoReadOnlyCallback() {
        runBlocking {
            val existing = temp.newFile("known-good.backup").apply { writeText("keep") }
            val data = "123456789".toByteArray()
            val offsets = mutableListOf<Pair<Long, Int>>()
            var leased: CloudRestoreReader? = null
            val restored = withCloudRestoreStage(temp.root, {
                assertEquals(listOf(existing.name), temp.root.list()?.toList())
                "snapshot"
            }) { snapshot, stage ->
                assertEquals("snapshot", snapshot)
                val path = cloudRestoreChild(stage.directory, "payload")
                downloadStagedCloudFile(path, data.size.toLong(), chunkSize = 4) { range ->
                    offsets += range.offset to range.length
                    val part = data.copyOfRange(range.offset.toInt(), range.last.toInt() + 1)
                    validateCloudRestoreRangeHeaders(
                        range, 206, "bytes ${range.offset}-${range.last}/${range.total}",
                        part.size.toString(), null,
                    )
                    var cursor = 0
                    readCloudRestoreRangeBody(range.length) { buffer, offset, max ->
                        if (cursor == part.size) -1 else {
                            val n = minOf(max, 2, part.size - cursor)
                            part.copyInto(buffer, offset, cursor, cursor + n)
                            cursor += n
                            n
                        }
                    }
                }
                leased = stage.reader
                stage.reader.readFile(listOf("payload")) { it.readBytes() }
            }
            assertArrayEquals(data, restored)
            assertEquals(listOf(0L to 4, 4L to 4, 8L to 1), offsets)
            assertFalse(runCatching { leased!!.readFile(listOf("payload")) { it.read() } }.isSuccess)
            assertEquals(listOf(existing.name), temp.root.list()?.toList())
            assertEquals("keep", existing.readText())
        }
    }

    @Test
    fun missingOrExpiredSnapshotDoesNotAllocateStageOrConsume() {
        runBlocking {
            val existing = temp.newFile("known-good.backup").apply { writeText("keep") }
            for (status in listOf(404, 401)) {
                val failure = DirectCloudException(status, "unavailable snapshot")
                val thrown = runCatching {
                    withCloudRestoreStage<String, Unit>(temp.root, { throw failure }) { _, _ ->
                        error("Consumer called for unavailable snapshot")
                    }
                }.exceptionOrNull()
                assertSame(failure, thrown)
                assertEquals(listOf(existing.name), temp.root.list()?.toList())
                assertEquals("keep", existing.readText())
            }
        }
    }

    @Test
    fun expiredMidStreamInterruptedCancelledAndShortResponsesPreserveOldBackup() {
        runBlocking {
            val existing = temp.newFile("known-good.backup").apply { writeText("keep") }
            val problems = listOf(
                DirectCloudException(401, "expired authorization"),
                IOException("provider disconnected"),
                CancellationException("cancelled"),
                null,
            )
            for (failure in problems) {
                var consumerCalled = false
                val offsets = mutableListOf<Long>()
                val thrown = runCatching {
                    withCloudRestoreStage(temp.root, { "snapshot" }) { _, stage ->
                        downloadStagedCloudFile(cloudRestoreChild(stage.directory, "payload"), 8, chunkSize = 4) { range ->
                            offsets += range.offset
                            if (range.offset == 0L) ByteArray(4) else failure?.let { throw it } ?: ByteArray(3)
                        }
                        consumerCalled = true
                    }
                }.exceptionOrNull()
                if (failure == null) assertTrue(thrown is IOException) else assertSame(failure, thrown)
                assertFalse(consumerCalled)
                assertEquals(listOf(0L, 4L), offsets)
                assertEquals(listOf(existing.name), temp.root.list()?.toList())
                assertEquals("keep", existing.readText())
            }
        }
    }

    @Test
    fun rangeResponseRequiresExactBoundsTotalLengthAndIdentityEncoding() {
        val range = CloudRestoreRange(4, 4, 12)
        validateCloudRestoreRangeHeaders(range, 206, "bytes 4-7/12", "4", "identity")
        val invalidRanges = listOf(null, "bytes 0-3/12", "bytes 4-6/12", "bytes 4-7/13", "bytes 4-7/*")
        for (contentRange in invalidRanges) {
            assertTrue(runCatching {
                validateCloudRestoreRangeHeaders(range, 206, contentRange, null, null)
            }.exceptionOrNull() is IOException)
        }
        assertTrue(runCatching {
            validateCloudRestoreRangeHeaders(range, 206, "bytes 4-7/12", "3", null)
        }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            validateCloudRestoreRangeHeaders(range, 206, "bytes 4-7/12", "4", "gzip")
        }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            validateCloudRestoreRangeHeaders(range, 200, null, null, null)
        }.exceptionOrNull() is IOException)
        validateCloudRestoreRangeHeaders(CloudRestoreRange(0, 4, 4), 200, null, "4", null)
    }

    @Test
    fun boundedBodyRejectsTruncatedOrExcessPayloads() {
        runBlocking {
            assertTrue(runCatching {
                readCloudRestoreRangeBody(4) { _, _, _ -> -1 }
            }.exceptionOrNull() is IOException)
            assertTrue(runCatching {
                readCloudRestoreRangeBody(4) { _, _, requested -> requested }
            }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun emptyFileDoesNotRequestAByteRange() {
        runBlocking {
            val len = withCloudRestoreStage(temp.root, { "snapshot" }) { _, stage ->
                downloadStagedCloudFile(cloudRestoreChild(stage.directory, "empty"), 0) { error("Unneeded range") }
                stage.reader.readFile(listOf("empty")) { it.readBytes().size }
            }
            assertEquals(0, len)
            assertTrue(temp.root.list().orEmpty().isEmpty())
        }
    }
}
