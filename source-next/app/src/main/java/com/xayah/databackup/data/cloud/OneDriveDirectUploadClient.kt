package com.xayah.databackup.data.cloud

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable

/**
 * Thin Microsoft Graph v1.0 uploader for OneDrive.
 *
 * Upload-session PUT requests intentionally omit the Authorization header because the opaque
 * upload URL contains the authorization context supplied by Microsoft.
 */
class OneDriveDirectUploadClient : Closeable {
    companion object {
        const val CHUNK_GRANULARITY = 320 * 1024
        const val DEFAULT_CHUNK_SIZE = 16 * CHUNK_GRANULARITY // 5 MiB
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

    suspend fun createSession(
        accessToken: String,
        remoteDirectory: String,
        fileName: String,
    ): DirectUploadSession {
        require(accessToken.isNotBlank()) { "Missing Microsoft access token." }
        require(fileName.isNotBlank()) { "Missing OneDrive file name." }

        val path = (remoteDirectory.trim('/') + "/" + fileName)
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("/") { Uri.encode(it) }
        val body = buildJsonObject {
            put("item", buildJsonObject {
                put("@microsoft.graph.conflictBehavior", "replace")
                put("name", fileName)
            })
        }
        val response = mClient.post(
            "https://graph.microsoft.com/v1.0/me/drive/root:/$path:/createUploadSession"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            throw DirectCloudException(response.status.value, "OneDrive upload-session creation failed.")
        }
        val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val uploadUrl = json["uploadUrl"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("OneDrive did not return an upload URL.")
        val expiresAt = json["expirationDateTime"]?.jsonPrimitive?.content
        return DirectUploadSession(uploadUrl = uploadUrl, expiresAt = expiresAt)
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
                "OneDrive non-final chunks must be multiples of 320 KiB."
            }
        }

        val end = offset + bytes.size - 1
        val response = mClient.put(session.uploadUrl) {
            header(HttpHeaders.ContentLength, bytes.size)
            header("Content-Range", "bytes $offset-$end/$totalBytes")
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }

        if (response.status.value == 200 || response.status.value == 201) {
            return DirectUploadProgress(completed = true, nextOffset = totalBytes)
        }
        if (response.status.value == 202) {
            val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val next = json["nextExpectedRanges"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.content
                ?.substringBefore('-')
                ?.toLongOrNull()
                ?: (offset + bytes.size)
            return DirectUploadProgress(completed = false, nextOffset = next)
        }
        throw DirectCloudException(response.status.value, "OneDrive chunk upload failed.")
    }
}
