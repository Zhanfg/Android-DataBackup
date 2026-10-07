package com.xayah.databackup.data.cloud

enum class CloudProvider {
    GOOGLE_DRIVE,
    ONEDRIVE,
}

object CloudAuthorizationScopes {
    const val GOOGLE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val ONEDRIVE_APP_FOLDER = "Files.ReadWrite.AppFolder"
}

data class CloudRemoteItem(
    val id: String,
    val name: String,
    val size: Long? = null,
    val isDirectory: Boolean,
    val modifiedAt: String? = null,
)

data class DirectUploadSession(
    val uploadUrl: String,
    val expiresAt: String? = null,
) {
    override fun toString(): String =
        "DirectUploadSession(uploadUrl=<redacted>, expiresAt=$expiresAt)"
}

data class DirectUploadProgress(
    val completed: Boolean,
    val nextOffset: Long,
    val expiresAt: String? = null,
)

class DirectCloudException(
    val statusCode: Int,
    message: String,
) : Exception(message)

internal fun validateUploadChunk(offset: Long, totalBytes: Long, bytes: ByteArray) {
    require(offset >= 0) { "Upload offset must be non-negative." }
    require(totalBytes >= 0) { "Total byte count must be non-negative." }
    require(bytes.isNotEmpty()) { "Upload chunk must not be empty." }
    require(offset + bytes.size <= totalBytes) { "Upload chunk exceeds declared file size." }
}

internal fun validateDownloadRange(offset: Long, length: Int) {
    require(offset >= 0) { "Download offset must be non-negative." }
    require(length > 0) { "Download length must be positive." }
}

internal fun nextOffsetFromAcknowledgedRange(rangeHeader: String?): Long =
    rangeHeader
        ?.substringAfterLast('-')
        ?.trim()
        ?.toLongOrNull()
        ?.plus(1)
        ?: 0L

internal fun nextOffsetFromMissingRanges(ranges: Iterable<String>): Long? =
    ranges.mapNotNull { range ->
        range.substringBefore('-').trim().toLongOrNull()
    }.minOrNull()
