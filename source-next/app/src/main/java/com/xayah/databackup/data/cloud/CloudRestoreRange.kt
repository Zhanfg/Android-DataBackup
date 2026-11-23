package com.xayah.databackup.data.cloud

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

internal const val CLOUD_RESTORE_CHUNK_BYTES = 4 * 1024 * 1024

internal data class CloudRestoreRange(val offset: Long, val length: Int, val total: Long) {
    init {
        require(offset >= 0 && length in 1..CLOUD_RESTORE_CHUNK_BYTES && total > 0)
        require(offset < total && length.toLong() <= total - offset)
    }

    val last: Long get() = offset + length - 1
    val requestHeader: String get() = "bytes=$offset-$last"
}

private val contentRangePattern = Regex("""bytes ([0-9]+)-([0-9]+)/([0-9]+)""", RegexOption.IGNORE_CASE)

internal fun validateCloudRestoreRangeHeaders(
    range: CloudRestoreRange,
    status: Int,
    contentRange: String?,
    contentLength: String?,
    contentEncoding: String?,
) {
    if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
        throw IOException("Encoded cloud restore responses are not supported.")
    }
    if (contentLength != null && contentLength.trim().toLongOrNull() != range.length.toLong()) {
        throw IOException("Cloud restore response length does not match the requested range.")
    }
    when (status) {
        200 -> if (range.offset != 0L || range.length.toLong() != range.total || contentRange != null) {
            throw IOException("Cloud storage ignored the requested range.")
        }
        206 -> {
            val fields = contentRangePattern.matchEntire(contentRange?.trim().orEmpty())?.groupValues
            if (fields?.get(1)?.toLongOrNull() != range.offset ||
                fields?.get(2)?.toLongOrNull() != range.last ||
                fields?.get(3)?.toLongOrNull() != range.total
            ) throw IOException("Cloud storage returned a different range or file size.")
        }
        else -> throw DirectCloudException(status, "Cloud restore range request failed.")
    }
}

/** Reads exactly one bounded range. EOF must follow the expected bytes. */
internal suspend fun readCloudRestoreRangeBody(
    length: Int,
    read: suspend (buffer: ByteArray, offset: Int, length: Int) -> Int,
): ByteArray {
    require(length in 1..CLOUD_RESTORE_CHUNK_BYTES)
    val buffer = ByteArray(length)
    var received = 0
    while (received < length) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer, received, length - received)
        if (count <= 0 || count > length - received) throw IOException("Cloud restore response ended early.")
        received += count
    }
    if (read(ByteArray(1), 0, 1) != -1) throw IOException("Cloud restore response exceeded the requested range.")
    return buffer
}

internal suspend fun downloadStagedCloudFile(
    path: File,
    size: Long,
    chunkSize: Int = CLOUD_RESTORE_CHUNK_BYTES,
    readRange: suspend (CloudRestoreRange) -> ByteArray,
) {
    require(size >= 0 && chunkSize in 1..CLOUD_RESTORE_CHUNK_BYTES)
    check(path.createNewFile()) { "Restore staging file already exists." }
    path.outputStream().use { output ->
        var offset = 0L
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val range = CloudRestoreRange(offset, minOf(chunkSize.toLong(), size - offset).toInt(), size)
            val bytes = readRange(range)
            currentCoroutineContext().ensureActive()
            if (bytes.size != range.length) throw IOException("Incomplete cloud restore range.")
            output.write(bytes)
            offset += range.length
        }
    }
}
