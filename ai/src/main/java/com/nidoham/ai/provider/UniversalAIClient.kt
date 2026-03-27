package com.nidoham.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun execute(
        baseUrl: String,
        endpoint: String,
        headers: List<CustomHeader> = emptyList(),
        body: List<CustomBody> = emptyList(),
        forceStream: Boolean = false
    ): Flow<String> = flow {
        val url = baseUrl.trimEnd('/') + "/" + endpoint.trimStart('/')
        val jsonBody = JsonObject(body.associate { it.key to it.value })
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(jsonBody)
            .apply { headers.forEach { addHeader(it.name, it.value) } }
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit("{\"error\":true,\"code\":${response.code},\"message\":\"${response.body.string()}\"}")
                return@flow
            }

            val streaming = forceStream ||
                    response.body.contentType()?.subtype?.contains("event-stream") == true

            if (streaming) {
                response.body.source().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (line.isBlank()) continue
                        when {
                            line.startsWith("data: ") -> {
                                val data = line.removePrefix("data: ").trim()
                                if (data != "[DONE]") emit(data)
                            }
                            line.startsWith("{") || line.startsWith("[") -> emit(line)
                        }
                    }
                }
            } else {
                val full = response.body.string()
                if (full.isNotBlank()) emit(full)
            }
        } catch (e: Exception) {
            emit("{\"error\":true,\"message\":\"${e.message}\"}")
        }
    }.flowOn(Dispatchers.IO)
}