package com.nidoham.ai.provider

import kotlinx.serialization.Serializable

/**
 * Represents a single custom HTTP header for a provider call.
 *
 * @param name The header name (e.g. "Authorization").
 * @param value The header value.
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)