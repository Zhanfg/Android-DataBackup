package com.xayah.databackup.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable

class GoogleDriveDirectUploadClient : Closeable {
    companion object {
        const val CHUNK_GRANULARITY = 256 * 1024
        const val DEFAULT_CHUNK_SIZE = 20 * CHUNK_GRANULARITY // 5 MiB
    }

    private val mJson = Json { ignoreUnknownKeys = true }
    private val mClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
    }

    override fun close() = mClient.close()

    suspend fun listChildren(accessToken: String, parentId: String): List<CloudRemoteItem> {
        require(accessToken.isNotBlank()) { "Missing Google access token." }
        require(parentId.isNotBlank()) { "Missing Drive parent id." }

        val items = mutableListOf<CloudRemoteItem>()
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        do {
            val response = mClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                parameter("q", "'$parentId' in parents and trashed = false")
                parameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                parameter("pageSize", 1000)
                pageToken?.let { parameter("pageToken", it) }
            }
            if (!response.status.isSuccess()) {
                throw DirectCloudException(response.status.value, "Google Drive list failed.")
            }

            val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
            json["files"]?.jsonArray?.forEach { value ->
                val item = value.jsonObject
                items += CloudRemoteItem(
                    id = item.getValue("id").jsonPrimitive.content,
                    name = item.getValue("name").jsonPrimitive.content,
                    size = item["size"]?.jsonPrimitive?.longOrNull,
                    isDirectory = item["mimeType"]?.jsonPrimitive?.content == "application/vnd.google-apps.folder",
                    modifiedAt = item["modifiedTime"]?.jsonPrimitive?.contentOrNull,
                )
            }
            pageToken = json["nextPageToken"]?.jsonPrimitive?.contentOrNull
            if (pageToken != null && !seenTokens.add(pageToken!!)) {
                error("Google Drive returned a repeated page token.")
            }
        } while (pageToken != null)
        return items
    }

    suspend fun createFolder(accessToken: String, name: String, parentId: String? = null): CloudRemoteItem {
        require(accessToken.isNotBlank()) { "Missing Google access token." }
        require(name.isNotBlank()) { "Missing Drive folder name." }
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            parentId?.takeIf(String::isNotBlank)?.let { parent ->
                put("parents", buildJsonArray { add(parent) })
            }
        }
        val response = mClient.post("https://www.googleapis.com/drive/v3/files") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("fields", "id,name,mimeType,size,modifiedTime")
            contentType(ContentType.Application.Json)
            setBody(metadata.toString())
        }
        if (!response.status.isSuccess()) {
            throw DirectCloudException(response.status.value, "Google Drive folder creation failed.")
        }
        val item = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return CloudRemoteItem(
            id = item.getValue("id").jsonPrimitive.content,
            name = item.getValue("name").jsonPrimitive.content,
            size = item["size"]?.jsonPrimitive?.longOrNull,
            isDirectory = true,
            modifiedAt = item["modifiedTime"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun downloadRange(accessToken: String, fileId: String, offset: Long, length: Int): ByteArray {
        require(accessToken.isNotBlank()) { "Missing Google access token." }
        require(fileId.isNotBlank()) { "Missing Drive file id." }
        validateDownloadRange(offset, length)
        val response = mClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
            parameter("alt", "media")
        }
        if (response.status.value != 200 && response.status.value != 206) {
            throw DirectCloudException(response.status.value, "Google Drive range download failed.")
        }
        return response.body()
    }

    suspend fun deleteItem(accessToken: String, fileId: String) {
        require(accessToken.isNotBlank()) { "Missing Google access token." }
        require(fileId.isNotBlank()) { "Missing Drive file id." }
        val response = mClient.delete("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (response.status.value != 204 && response.status.value != 404) {
            throw DirectCloudException(response.status.value, "Google Drive delete failed.")
        }
    }

    suspend fun createSession(
        accessToken: String,
        fileName: String,
        totalBytes: Long,
        parentId: String? = null,
        existingFileId: String? = null,
        mimeType: String = "application/octet-stream",
    ): DirectUploadSession {
        require(accessToken.isNotBlank()) { "Missing Google access token." }
        require(fileName.isNotBlank()) { "Missing Drive file name." }
        require(totalBytes >= 0) { "Invalid file size." }

        val metadata = buildJsonObject {
            put("name", fileName)
            if (existingFileId == null) {
                parentId?.takeIf(String::isNotBlank)?.let { parent ->
                    put("parents", buildJsonArray { add(parent) })
                }
            }
        }
        val uploadEndpoint = existingFileId
            ?.let { "https://www.googleapis.com/upload/drive/v3/files/$it" }
            ?: "https://www.googleapis.com/upload/drive/v3/files"
        val response = if (existingFileId == null) {
            mClient.post(uploadEndpoint) {
                parameter("uploadType", "resumable")
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("X-Upload-Content-Type", mimeType)
                header("X-Upload-Content-Length", totalBytes)
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
        } else {
            mClient.patch(uploadEndpoint) {
                parameter("uploadType", "resumable")
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("X-Upload-Content-Type", mimeType)
                header("X-Upload-Content-Length", totalBytes)
                contentType(ContentType.Application.Json)
                setBody(metadata.toString())
            }
        }
        if (!response.status.isSuccess()) {
            throw DirectCloudException(response.status.value, "Google Drive upload-session creation failed.")
        }
        val uploadUrl = response.headers[HttpHeaders.Location]
            ?: throw IllegalStateException("Google Drive did not return a resumable upload URL.")
        return DirectUploadSession(uploadUrl)
    }

    suspend fun queryProgress(
        session: DirectUploadSession,
        totalBytes: Long,
    ): DirectUploadProgress {
        require(totalBytes > 0) { "Upload size must be positive." }
        val response = mClient.put(session.uploadUrl) {
            header(HttpHeaders.ContentLength, 0)
            header("Content-Range", "bytes */$totalBytes")
            setBody(ByteArrayContent(ByteArray(0), ContentType.Application.OctetStream))
        }
        if (response.status.isSuccess()) {
            return DirectUploadProgress(completed = true, nextOffset = totalBytes)
        }
        if (response.status.value == 308) {
            return DirectUploadProgress(
                completed = false,
                nextOffset = nextOffsetFromAcknowledgedRange(response.headers["Range"]),
            )
        }
        throw DirectCloudException(response.status.value, "Google Drive upload-session status query failed.")
    }

    suspend fun uploadChunk(
        session: DirectUploadSession,
        offset: Long,
        totalBytes: Long,
        bytes: ByteArray,
    ): DirectUploadProgress {
        validateUploadChunk(offset, totalBytes, bytes)
        val isFinal = offset + bytes.size == totalBytes
        if (!isFinal) {
            require(bytes.size % CHUNK_GRANULARITY == 0) {
                "Google Drive non-final chunks must be multiples of 256 KiB."
            }
        }
        val end = offset + bytes.size - 1
        val response = mClient.put(session.uploadUrl) {
            header(HttpHeaders.ContentLength, bytes.size)
            header("Content-Range", "bytes $offset-$end/$totalBytes")
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }

        if (response.status.isSuccess()) {
            return DirectUploadProgress(completed = true, nextOffset = totalBytes)
        }
        if (response.status.value == 308) {
            return DirectUploadProgress(
                completed = false,
                nextOffset = nextOffsetFromAcknowledgedRange(response.headers["Range"]),
            )
        }
        runCatching { response.bodyAsText() }
        throw DirectCloudException(response.status.value, "Google Drive chunk upload failed.")
    }
}
