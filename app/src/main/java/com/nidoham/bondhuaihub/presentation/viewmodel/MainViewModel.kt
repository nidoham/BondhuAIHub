package com.nidoham.bondhuaihub.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.ai.provider.CustomBody
import com.nidoham.ai.provider.CustomHeader
import com.nidoham.ai.provider.UniversalAIClient
import com.nidoham.bondhuaihub.Config
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val aiClient: UniversalAIClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun sendPrompt(prompt: String) {
        if (prompt.isBlank()) return

        _uiState.update { it.copy(isLoading = true, thoughtText = "", responseText = "", error = null) }

        val headers = listOf(
            CustomHeader("x-goog-api-key", Config.API_KEY),
            CustomHeader("Content-Type", "application/json")
        )

        val body = listOf(
            CustomBody("contents", buildJsonArray {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }),
            CustomBody("generationConfig", buildJsonObject {
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", -1)
                }
            })
        )

        viewModelScope.launch {
            try {
                val modelName = "gemini-3-flash-preview"
                aiClient.execute(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    endpoint = "models/$modelName:streamGenerateContent?alt=sse",
                    headers = headers,
                    body = body,
                    forceStream = true
                ).collect { jsonString ->
                    val parts = try {
                        Json.parseToJsonElement(jsonString)
                            .jsonObject["candidates"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("content")
                            ?.jsonObject?.get("parts")
                            ?.jsonArray
                    } catch (e: Exception) { null } ?: return@collect

                    parts.forEach { part ->
                        val obj = part.jsonObject
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val isThought = obj["thought"]?.jsonPrimitive?.booleanOrNull == true

                        _uiState.update { state ->
                            if (isThought) state.copy(isLoading = false, thoughtText = state.thoughtText + text)
                            else state.copy(isLoading = false, responseText = state.responseText + text)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}

data class UiState(
    val isLoading: Boolean = false,
    val thoughtText: String = "",
    val responseText: String = "",
    val error: String? = null
)