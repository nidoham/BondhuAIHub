package com.nidoham.ai.provider.exception

/**
 * Per-call timeout and retry configuration for [UniversalAIClient].
 *
 * @param connectTimeoutSeconds TCP connect timeout.
 * @param readTimeoutSeconds Response read timeout.
 * @param writeTimeoutSeconds Request write timeout.
 * @param retryCount How many times to retry on [AIClientException.NetworkException] or [AIClientException.TimeoutException].
 * @param retryDelayMillis Delay between retries in milliseconds.
 */
data class RequestConfig(
    val connectTimeoutSeconds: Long = 60L,
    val readTimeoutSeconds: Long = 60L,
    val writeTimeoutSeconds: Long = 60L,
    val retryCount: Int = 0,
    val retryDelayMillis: Long = 1_000L
)