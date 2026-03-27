package com.nidoham.ai.provider

import com.nidoham.ai.provider.exception.AIClientException
import com.nidoham.ai.provider.exception.HttpMethod
import com.nidoham.ai.provider.exception.RequestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Universal HTTP client for ANY AI provider endpoint.
 *
 * Supports: OpenAI, Anthropic Claude, Google Gemini, Cohere, Mistral,
 * Azure OpenAI, Groq, Ollama, OpenRouter, DeepSeek, Together AI,
 * এবং যেকোনো OpenAI-compatible API.
 *
 * Features:
 * - Streaming (SSE) with proper [DONE] handling
 * - Non-streaming JSON responses
 * - Automatic retry on network/timeout errors
 * - Provider-specific error normalization
 * - Mid-stream error handling (OpenRouter, etc.)
 * - SSE comment/ping filtering
 */
@Singleton
class UniversalAIClient @Inject constructor() {

    private val defaultClient: OkHttpClient = buildClient(RequestConfig())

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes an HTTP request and emits response chunks as a [Flow].
     *
     * @param baseUrl Provider base URL (e.g. "https://api.openai.com" বা "https://openrouter.ai/api/v1").
     * @param endpoint Path segment (e.g. "chat/completions").
     * @param method HTTP verb — defaults to POST.
     * @param headers Additional request headers.
     * @param body Key-value pairs serialised as the JSON request body.
     * @param queryParams URL query parameters.
     * @param forceStream Treat response as SSE regardless of Content-Type.
     * @param config Per-call timeouts and retry settings.
     * @param appName Optional app name for OpenRouter attribution (HTTP-Referer).
     * @param appTitle Optional app title for OpenRouter (X-Title).
     * @throws AIClientException on network, timeout, or HTTP errors.
     */
    fun execute(
        baseUrl: String,
        endpoint: String,
        method: HttpMethod = HttpMethod.POST,
        headers: List<CustomHeader> = emptyList(),
        body: List<CustomBody> = emptyList(),
        queryParams: Map<String, String> = emptyMap(),
        forceStream: Boolean = false,
        config: RequestConfig = RequestConfig(),
        appName: String? = null,
        appTitle: String? = null
    ): Flow<String> = flow {
        val client = clientFor(config)
        val url = buildUrl(baseUrl, endpoint, queryParams)
        val requestBody = body.toRequestBody(method)
        val request = buildRequest(
            url = url,
            method = method,
            headers = headers,
            body = requestBody,
            appName = appName,
            appTitle = appTitle
        )

        var attempt = 0
        while (true) {
            try {
                client.newCall(request).execute().use { response ->
                    processResponse(response, forceStream) { chunk ->
                        // Mid-stream error চেক (OpenRouter স্টাইল)
                        if (isMidStreamError(chunk)) {
                            val errorMsg = extractErrorFromChunk(chunk)
                            throw AIClientException.HttpException(200, errorMsg)
                        }
                        emit(chunk)
                    }
                }
                return@flow
            } catch (e: IOException) {
                val wrapped = wrapIOException(e)
                when (wrapped) {
                    is AIClientException.NetworkException,
                    is AIClientException.TimeoutException -> {
                        handleRetry(wrapped, attempt, config)
                    }
                    else -> throw wrapped
                }
            } catch (e: AIClientException.NetworkException) {
                handleRetry(e, attempt, config)
            } catch (e: AIClientException.TimeoutException) {
                handleRetry(e, attempt, config)
            }
            attempt++
            delay(config.retryDelayMillis)
        }
    }.flowOn(Dispatchers.IO)

    // ── Request building ──────────────────────────────────────────────────────

    private fun buildUrl(
        baseUrl: String,
        endpoint: String,
        queryParams: Map<String, String>
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanEndpoint = endpoint.trimStart('/')
        val base = "$cleanBase/$cleanEndpoint"

        if (queryParams.isEmpty()) return base

        val query = queryParams.entries.joinToString("&") { (k, v) ->
            "${k.urlEncode()}=${v.urlEncode()}"
        }
        return "$base?$query"
    }

    private fun List<CustomBody>.toRequestBody(method: HttpMethod): RequestBody? {
        return when (method) {
            HttpMethod.GET, HttpMethod.DELETE -> null
            else -> {
                if (isEmpty()) {
                    "{}".toRequestBody(JSON_MEDIA_TYPE)
                } else {
                    JsonObject(associate { it.key to it.value })
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                }
            }
        }
    }

    private fun buildRequest(
        url: String,
        method: HttpMethod,
        headers: List<CustomHeader>,
        body: RequestBody?,
        appName: String?,
        appTitle: String?
    ): Request {
        val requestBuilder = Request.Builder().url(url)

        // Custom headers যোগ করা
        headers.forEach { requestBuilder.addHeader(it.name, it.value) }

        // ডিফল্ট headers
        if (headers.none { it.name.equals("Content-Type", ignoreCase = true) }) {
            requestBuilder.addHeader("Content-Type", "application/json")
        }
        if (headers.none { it.name.equals("Accept", ignoreCase = true) }) {
            requestBuilder.addHeader("Accept", "application/json, text/event-stream")
        }

        // OpenRouter attribution headers
        appName?.let { requestBuilder.addHeader("HTTP-Referer", it) }
        appTitle?.let {
            requestBuilder.addHeader("X-Title", it)
            requestBuilder.addHeader("X-OpenRouter-Title", it)
        }

        // Method স্পেসিফিক বডি হ্যান্ডলিং
        when (method) {
            HttpMethod.GET -> requestBuilder.get()
            HttpMethod.DELETE -> requestBuilder.delete(body)
            HttpMethod.POST -> requestBuilder.post(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            HttpMethod.PUT -> requestBuilder.put(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
            HttpMethod.PATCH -> requestBuilder.patch(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE))
        }

        return requestBuilder.build()
    }

    // ── Response processing ───────────────────────────────────────────────────

    private suspend fun processResponse(
        response: Response,
        forceStream: Boolean,
        emit: suspend (String) -> Unit
    ) {
        if (!response.isSuccessful) {
            val errorBody = runCatching { response.body.string() }.getOrDefault("(unreadable)")
            Timber.e("HTTP ${response.code}: $errorBody")
            throw AIClientException.HttpException(response.code, errorBody)
        }

        val contentType = response.body.contentType()?.toString() ?: ""
        val isStreaming = forceStream ||
                contentType.contains("event-stream", ignoreCase = true) ||
                contentType.contains("text/plain", ignoreCase = true) ||
                response.headers["X-Accel-Buffering"] == "no"

        if (isStreaming) {
            parseSSE(response, emit)
        } else {
            val full = response.body.string()
            if (full.isNotBlank()) emit(full)
        }
    }

    private suspend fun parseSSE(
        response: Response,
        emit: suspend (String) -> Unit
    ) {
        response.body.source().use { source ->
            val buffer = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue

                // SSE comment/ping ফিল্টারিং (OpenRouter, Anthropic)
                if (line.startsWith(":")) {
                    Timber.d("SSE comment ignored: $line")
                    continue
                }

                // খালি লাইন মানে এভেন্ট শেষ
                if (line.isBlank()) {
                    if (buffer.isNotEmpty()) {
                        emit(buffer.toString())
                        buffer.clear()
                    }
                    continue
                }

                when {
                    line.startsWith("data: ") -> {
                        val data = line.removePrefix("data: ").trim()

                        // OpenAI/Anthropic [DONE] মার্কার
                        if (data == "[DONE]") {
                            if (buffer.isNotEmpty()) {
                                emit(buffer.toString())
                                buffer.clear()
                            }
                            continue
                        }

                        // JSON ডেটা এমিট করা
                        if (data.startsWith("{") || data.startsWith("[")) {
                            emit(data)
                        } else {
                            // নন-JSON ডেটা (রেয়ার কেস)
                            buffer.append(data)
                        }
                    }

                    line.startsWith("event: ") -> {
                        // এভেন্ট টাইপ (Anthropic/OpenRouter) - ইগনোর
                        continue
                    }

                    line.startsWith("id: ") || line.startsWith("retry: ") -> {
                        // SSE মেটাডেটা - ইগনোর
                        continue
                    }
                    // ফলব্যাক: JSON লাইন সরাসরি
                    line.startsWith("{") || line.startsWith("[") -> {
                        emit(line)
                    }
                }
            }

            // বাকি বাফার এমিট করা
            if (buffer.isNotEmpty()) {
                emit(buffer.toString())
            }
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private fun isMidStreamError(chunk: String): Boolean {
        return try {
            val json = JSON.parseToJsonElement(chunk).jsonObject
            json.containsKey("error") ||
                    (json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content == "error")
        } catch (e: Exception) {
            false
        }
    }

    private fun extractErrorFromChunk(chunk: String): String {
        return try {
            val json = JSON.parseToJsonElement(chunk).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: json["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                ?: "Unknown mid-stream error"
        } catch (e: Exception) {
            chunk
        }
    }

    private fun handleRetry(
        e: AIClientException,
        attempt: Int,
        config: RequestConfig
    ) {
        if (attempt >= config.retryCount) throw e
        Timber.w(e, "Attempt ${attempt + 1}/${config.retryCount + 1} failed — retrying in ${config.retryDelayMillis}ms")
    }

    private fun wrapIOException(e: IOException): AIClientException = when (e) {
        is SocketTimeoutException -> AIClientException.TimeoutException("Request timed out", e)
        is UnknownHostException -> AIClientException.NetworkException("Unknown host — no network?", e)
        is java.net.ConnectException -> AIClientException.NetworkException("Connection refused", e)
        is java.net.SocketException -> AIClientException.NetworkException("Socket error: ${e.message}", e)
        is javax.net.ssl.SSLException -> AIClientException.NetworkException("SSL/TLS error: ${e.message}", e)
        is java.io.InterruptedIOException -> AIClientException.TimeoutException("Request interrupted", e)
        else -> AIClientException.NetworkException("Network error: ${e.message}", e)
    }

    // ── Client factory ────────────────────────────────────────────────────────

    private fun clientFor(config: RequestConfig): OkHttpClient =
        if (config == RequestConfig()) defaultClient
        else defaultClient.newBuilder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()

    private fun buildClient(config: RequestConfig): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}