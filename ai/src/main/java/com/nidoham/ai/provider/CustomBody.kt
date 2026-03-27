package com.nidoham.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
) {
    companion object {
        // এটি UI থেকে ইনপুট নেওয়ার সময় খুব কাজে লাগবে
        fun create(key: String, rawValue: String): CustomBody {
            val jsonElement = try {
                // চেষ্টা করছি স্ট্রিংটি কি আসলে JSON Array বা Object কিনা?
                // যেমন: "[1,2,3]" বা "{"role":"user"}"
                Json.parseToJsonElement(rawValue)
            } catch (e: Exception) {
                // যদি JSON না হয়, তবে এটিকে সাধারণ স্ট্রিং হিসেবে রাখছে
                JsonPrimitive(rawValue)
            }
            return CustomBody(key, jsonElement)
        }
    }
}