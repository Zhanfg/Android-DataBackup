package com.xayah.databackup.data.cloud

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable

class OneDriveDirectUploadClient : Closeable {
    companion object {
        const val CHUNK_GRANULARITY = 320 * 1024
        const val DEFAULT_CHUNK_SIZE = 16 * CHUNK_GRANULARITY // 5 MiB
        private const val GRAPH_ORIGIN = "https://graph.microsoft.com/"
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

    private fun encodePath(path: String): String =
        path.trim('/').split('/').filter(String::isNotBlank).joinToString("/") { Uri.encode(it) }

    private fun childrenUrl(remoteDirectory: String): String {
        val path = encodePath(remoteDirectory)
        return if (path.isBlank()) {
            "https://graph.microsoft.com/v1.0/me/drive/special/approot/children"
        } else {
            "https://graph.microsoft.com/v1.0/me/drive/special/approot:/$path:/children"
        }
    }

    suspend fun listChildren(accessToken: String, remoteDirectory: String = ""): List<CloudRemoteItem> {
        require(accessToken.isNotBlank()) { "Missing Microsoft access token." }

        val items = mutableListOf<CloudRemoteItem>()
        var nextUrl: String? = childrenUrl(remoteDirectory)
        val seenUrls = mutableSetOf<String>()
        while (nextUrl != null) {
            require(nextUrl.startsWith(GRAPH_ORIGIN)) { "OneDrive returned an unexpected pagination origin." }
            if (!seenUrls.add(nextUrl)) error("OneDrive returned a repeated pagination URL.")

            val response = mClient.get(nextUrl) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            if (response.status.value !in 200..299) {
                throw DirectCloudException(response.status.value, "OneDrive list failed.")
            }

            val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
            json["value"]?.jsonArray?.forEach { value ->
                val item = value.jsonObject
                items += CloudRemoteItem(
                    id = item.getValue("id").jsonPrimitive.content,
                    name = item.getValue("name").jsonPrimitive.content,
                    size = item["size"]?.jsonPrimitive?.longOrNull,
                    isDirectory = item["folder"] != null,
                    modifiedAt = item["lastModifiedDateTime"]?.jsonPrimitive?.contentOrNull,
                )
            }
            nextUrl = json["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
        }
        return items
    }

    suspend fun createFolder(accessToken: String, remoteDirectory: String, name: String): CloudRemoteItem {
        require(accessToken.isNotBlank()) { "Missing Microsoft access token." }
        require(name.isNotBlank()) { "Missing OneDrive folder name." }
        val response = mClient.post(childrenUrl(remoteDirectory)) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("folder", buildJsonObject {})
                    put("@microsoft.graph.conflictBehavior", "fail")
                }.toString()
            )
        }
        if (response.status.value !in 200..299) {
            throw DirectCloudException(response.status.value, "OneDrive folder creation failed.")
        }
        val item = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return CloudRemoteItem(
            id = item.getValue("id").jsonPrimitive.content,
            name = item.getValue("name").jsonPrimitive.content,
            size = item["size"]?.jsonPrimitive?.longOrNull,
            isDirectory = true,
            modifiedAt = item["lastModifiedDateTime"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun downloadRange(accessToken: String, itemId: String, offset: Long, length: Int): ByteArray {
        require(accessToken.isNotBlank()) { "Missing Microsoft access token." }
        require(itemId.isNotBlank()) { "Missing OneDrive item id." }
        validateDownloadRange(offset, length)
        val response = mClient.get("https://graph.microsoft.com/v1.0/me/drive/items/$itemId/content") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
        }
        if (response.status.value != 200 && response.status.value != 206) {
            throw DirectCloudException(response.status.value, "OneDrive range download failed.")
        }
        return response.body()
    }

    suspend fun createSession(
        accessToken: String,
        remoteDirectory: String,
        fileName: String,
    ): DirectUploadSession {
        require(accessToken.isNotBlank()) { "Missing Microsoft access token." }
        require(fileName.isNotBlank()) { "Missing OneDrive file name." }

        val path = encodePath(remoteDirectory.trim('/') + "/" + fileName)
        val body = buildJsonObject {
            put("item", buildJsonObject {
                put("@microsoft.graph.conflictBehavior", "replace")
                put("name", fileName)
            })
        }
        val response = mClient.post(
            "https://graph.microsoft.com/v1.0/me/drive/special/approot:/$path:/createUploadSession"
        ) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (response.status.value !in 200..299) {
            throw DirectCloudException(response.status.value, "OneDrive upload-session creation failed.")
        }
        val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val uploadUrl = json["uploadUrl"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("OneDrive did not return an upload URL.")
        val expiresAt = json["expirationDateTime"]?.jsonPrimitive?.contentOrNull
        return DirectUploadSession(uploadUrl = uploadUrl, expiresAt = expiresAt)
    }

    suspend fun queryProgress(session: DirectUploadSession): DirectUploadProgress {
        val response = mClient.get(session.uploadUrl)
        if (response.status.value !in 200..299) {
            throw DirectCloudException(response.status.value, "OneDrive upload-session status query failed.")
        }
        val json = mJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val ranges = json["nextExpectedRanges"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val nextOffset = nextOffsetFromMissingRanges(ranges)
            ?: error("OneDrive upload session returned no missing range.")
        return DirectUploadProgress(
            completed = false,
            nextOffset = nextOffset,
            expiresAt = json["expirationDateTime"]?.jsonPrimitive?.contentOrNull,
        )
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
            val ranges = json["nextExpectedRanges"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            return DirectUploadProgress(
                completed = false,
                nextOffset = nextOffsetFromMissingRanges(ranges) ?: (offset + bytes.size),
                expiresAt = json["expirationDateTime"]?.jsonPrimitive?.contentOrNull,
            )
        }
        throw DirectCloudException(response.status.value, "OneDrive chunk upload failed.")
    }
}
