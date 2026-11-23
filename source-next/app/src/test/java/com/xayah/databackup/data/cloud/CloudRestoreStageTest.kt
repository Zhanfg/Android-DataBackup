package com.xayah.databackup.data.cloud

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CloudRestoreStageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun failedPreflightDoesNotCreateStageOrTouchExistingBackup() {
        runBlocking {
            val old = temp.newFile("existing.backup").apply { writeText("keep me") }
            val unavailable = IOException("snapshot unavailable")
            val failed = runCatching {
                withCloudRestoreStage(temp.root, { throw unavailable }) { _, _ -> error("not reached") }
            }
            assertSame(unavailable, failed.exceptionOrNull())
            assertEquals(listOf("existing.backup"), temp.root.list()?.toList())
            assertEquals("keep me", old.readText())
        }
    }

    @Test
    fun failureAndCancellationRemoveOnlyNewStage() {
        runBlocking {
            val old = temp.newFile("existing.backup").apply { writeText("keep me") }
            for (problem in listOf(IOException("interrupted transfer"), CancellationException("cancelled"))) {
                val failed = runCatching {
                    withCloudRestoreStage(temp.root, { "verified snapshot" }) { remote, stage ->
                        assertEquals("verified snapshot", remote)
                        cloudRestoreChild(stage.directory, "partial").writeText("discard")
                        throw problem
                    }
                }
                assertSame(problem, failed.exceptionOrNull())
                assertEquals(listOf("existing.backup"), temp.root.list()?.toList())
                assertEquals("keep me", old.readText())
            }
        }
    }

    @Test
    fun populatedStageIsReadableOnlyDuringLease() {
        runBlocking {
            lateinit var reader: CloudRestoreReader
            val result = withCloudRestoreStage(temp.root, { "verified snapshot" }) { _, stage ->
                reader = stage.reader
                val directory = cloudRestoreChild(stage.directory, "subfolder")
                assertTrue(directory.mkdir())
                cloudRestoreChild(directory, "entry").writeText("snapshot bytes")
                stage.reader.readFile(listOf("subfolder", "entry")) { input ->
                    input.bufferedReader().readText()
                }
            }
            assertEquals("snapshot bytes", result)
            assertTrue(temp.root.list().orEmpty().isEmpty())
            assertFalse(runCatching { reader.readFile(listOf("subfolder", "entry")) { it.read() } }.isSuccess)
        }
    }

    @Test
    fun onlySingleSafeChildComponentsAccepted() {
        val dir = temp.newFolder()
        for (component in listOf("", " ", ".", "..", "nested/entry", "bad\\entry", "bad\u0000entry")) {
            assertTrue(runCatching { cloudRestoreChild(dir, component) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("合规备份", cloudRestoreChild(dir, "合规备份").name)
    }
}
