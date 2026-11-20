package com.usool.map.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Thin OkHttp wrapper. Every request identifies the app, as the OSM service policies require. */
class Http(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(request(url, headers).build()) { it.readBytes().toString(Charsets.UTF_8) }

    suspend fun <T> getStream(url: String, headers: Map<String, String> = emptyMap(), block: (InputStream) -> T): T =
        execute(request(url, headers).build(), block)

    suspend fun postString(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): String = execute(request(url, headers).post(body.toRequestBody(contentType.toMediaType())).build()) {
        it.readBytes().toString(Charsets.UTF_8)
    }

    suspend fun postBytes(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = execute(request(url, headers).post(body.toRequestBody(contentType.toMediaType())).build()) {
        it.readBytes()
    }

    /**
     * Downloads [url] into [target], continuing from the bytes already in it. A server that does
     * not honour the range is detected (status 200 instead of 206) and the file is restarted.
     * [onBytes] receives the size on disk as it grows. Cancelling the coroutine keeps the partial
     * file, which is what makes pause and resume work.
     */
    suspend fun downloadTo(url: String, target: java.io.File, headers: Map<String, String> = emptyMap(), onBytes: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val have = if (target.isFile) target.length() else 0L
        val builder = request(url, headers)
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416) return@use // already complete
            if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code} from ${response.request.url.host}")
            val append = response.code == 206
            val body = response.body ?: throw IOException("Empty response")
            java.io.FileOutputStream(target, append).use { out ->
                var total = if (append) have else 0L
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        if (!isActive) throw kotlinx.coroutines.CancellationException("download paused")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    private suspend fun <T> execute(request: Request, block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, "HTTP ${response.code} from ${request.url.host}")
            }
            val stream = response.body?.byteStream() ?: throw IOException("Empty response from ${request.url.host}")
            block(stream)
        }
    }

    companion object {
        const val USER_AGENT = "UsoolMap/1.0 (Android; com.usool.map)"
    }
}
