package com.xayah.databackup.data.cloud

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Read-only access to the staged backup during the consumer callback. */
interface CloudRestoreReader {
    fun <T> readFile(pathSegments: List<String>, read: (InputStream) -> T): T
}

internal fun requireCloudRestoreChildName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name) {
        "Invalid cloud restore path component."
    }
}

internal fun cloudRestoreChild(parent: File, name: String): File {
    requireCloudRestoreChildName(name)
    val child = File(parent, name)
    check(child.canonicalFile == File(parent.canonicalFile, name)) {
        "Cloud restore path did not resolve to a direct child."
    }
    return child
}

internal class CloudRestoreStage(val directory: File) {
    private var open = true

    val reader: CloudRestoreReader = object : CloudRestoreReader {
        override fun <T> readFile(pathSegments: List<String>, read: (InputStream) -> T): T {
            check(open) { "Cloud restore staging lease is closed." }
            require(pathSegments.isNotEmpty())
            val file = pathSegments.fold(directory, ::cloudRestoreChild)
            check(file.isFile) { "Requested restore entry is not a file." }
            return file.inputStream().use(read)
        }
    }

    fun close() {
        open = false
        check(directory.deleteRecursively()) { "Could not clean cloud restore staging." }
    }
}

internal suspend fun <Remote, T> withCloudRestoreStage(
    cacheDirectory: File,
    preflight: suspend () -> Remote,
    consume: suspend (Remote, CloudRestoreStage) -> T,
): T {
    // Provider failure must occur before we touch the local filesystem.
    currentCoroutineContext().ensureActive()
    val remote = preflight()
    currentCoroutineContext().ensureActive()
    val cache = cacheDirectory.canonicalFile
    check(cache.isDirectory) { "Missing application cache directory." }
    val candidate = File(cache, "cloud-restore-${UUID.randomUUID()}")
    // mkdir succeeds only for a newly created directory, including on Android API 24.
    check(candidate.mkdir()) { "Could not create an exclusive cloud restore stage." }
    val stage = CloudRestoreStage(candidate)
    var failure: Throwable? = null
    try {
        return consume(remote, stage)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            stage.close()
        } catch (cleanupError: Throwable) {
            val original = failure
            if (original == null) throw cleanupError
            original.addSuppressed(cleanupError)
        }
    }
}
