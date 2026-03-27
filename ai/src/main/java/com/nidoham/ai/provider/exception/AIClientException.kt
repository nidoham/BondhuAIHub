package com.nidoham.ai.provider.exception

/**
 * Typed exception hierarchy for [com.nidoham.ai.provider.UniversalAIClient].
 * Thrown into the flow so callers can catch specific failure modes.
 */
sealed class AIClientException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** DNS failure or no network. */
    class NetworkException(message: String, cause: Throwable? = null) : AIClientException(message, cause)

    /** Connect / read / write timed out. */
    class TimeoutException(message: String, cause: Throwable? = null) : AIClientException(message, cause)

    /** Server returned a non-2xx status. */
    class HttpException(val code: Int, val errorBody: String?) : AIClientException("HTTP $code: $errorBody")

    /** Anything else unexpected. */
    class UnknownException(message: String, cause: Throwable? = null) : AIClientException(message, cause)
}