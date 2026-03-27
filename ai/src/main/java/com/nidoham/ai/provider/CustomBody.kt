package com.nidoham.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a single custom request body entry for a provider call.
 *
 * @param key The body field name.
 * @param value The field value as a flexible JSON element.
 */
@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)