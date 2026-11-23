package com.xayah.databackup.data.cloud

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable

internal suspend fun HttpResponse.readCloudRestoreRange(range: CloudRestoreRange): ByteArray {
    if (status.value != 200 && status.value != 206) {
        throw DirectCloudException(status.value, "Cloud restore range request failed.")
    }
    validateCloudRestoreRangeHeaders(
        range,
        status.value,
        headers[HttpHeaders.ContentRange],
        headers[HttpHeaders.ContentLength],
        headers[HttpHeaders.ContentEncoding],
    )
    val channel = bodyAsChannel()
    return readCloudRestoreRangeBody(range.length) { buffer, offset, length ->
        channel.readAvailable(buffer, offset, length)
    }
}
