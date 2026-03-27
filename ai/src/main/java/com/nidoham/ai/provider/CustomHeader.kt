package com.nidoham.ai.provider

import kotlinx.serialization.Serializable

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)