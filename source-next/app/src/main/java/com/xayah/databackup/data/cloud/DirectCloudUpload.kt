package com.xayah.databackup.data.cloud

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
