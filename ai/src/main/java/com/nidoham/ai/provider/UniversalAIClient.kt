package com.nidoham.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UniversalAIClient @Inject constructor() {

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun execute(
        baseUrl: String,
        endpoint: String,
        headers: List<CustomHeader>,
        body: List<CustomBody>
    ): Flow<String> = flow {

        // --- 1. Build Request Body ---
        val bodyMap = body.associate { it.key to it.value }
        val jsonObject = JsonObject(bodyMap)
        val jsonString = jsonFormat.encodeToString(jsonObject)

        val requestBody = jsonString.toRequestBody("application/json; charset=utf-8".toMediaType())

        // --- 2. Build URL and Headers ---
        val url = "$baseUrl/$endpoint"

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        headers.forEach { header ->
            requestBuilder.addHeader(header.name, header.value)
        }

        val request = requestBuilder.build()

        // --- 3. Execute Network Call ---
        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string() ?: "Unknown Error"
                emit("{\"error\": true, \"code\": ${response.code}, \"message\": \"$errorBody\"}")
                return@flow
            }

            // --- 4. Check Response Type (Streaming vs Normal) ---
            val isStreaming = response.body.contentType()?.subtype?.contains("event-stream") == true

            if (isStreaming) {
                // --- Streaming Logic (SSE) ---
                response.body.source().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line()
                        if (!line.isNullOrBlank()) {
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data != "[DONE]") {
                                    emit(data)
                                }
                            } else if (line.startsWith("{") || line.startsWith("[")) {
                                // Handle cases where JSON is sent without "data:" prefix
                                emit(line)
                            }
                        }
                    }
                }
            } else {
                // --- Normal JSON Logic ---
                val fullResponse = response.body.string()
                if (fullResponse.isNotBlank()) {
                    emit(fullResponse)
                }
            }

        } catch (e: Exception) {
            emit("{\"error\": true, \"message\": \"${e.message}\"}")
        }

    }.flowOn(Dispatchers.IO)
}