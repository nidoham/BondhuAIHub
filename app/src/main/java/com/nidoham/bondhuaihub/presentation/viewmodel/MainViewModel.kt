package com.nidoham.bondhuaihub.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.ai.provider.CustomBody
import com.nidoham.ai.provider.CustomHeader
import com.nidoham.ai.provider.UniversalAIClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject // JSON পার্সিংয়ের জন্য
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val aiClient: UniversalAIClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun sendPrompt(prompt: String) {
        if (prompt.isBlank()) return

        _uiState.update { it.copy(isLoading = true, responseText = "", error = null) }

        // --- Gemini API Configuration ---
        val apiKey = "AIzaSyC4OgaLA_ocXFnLJCpmJ2YkgepVEg47TSU"

        val headers = listOf(
            // Gemini-তে সাধারণত হেডারেই এপিআই কি দেওয়া নিরাপদ
            CustomHeader("x-goog-api-key", apiKey),
            CustomHeader("Content-Type", "application/json")
        )

        // Gemini-র বডি স্ট্রাকচার OpenAI থেকে আলাদা
        // JSON Structure: {"contents": [{"parts":[{"text": "prompt"}]}]}
        val body = listOf(
            CustomBody.create("contents", "[{\"parts\":[{\"text\":\"$prompt\"}]}]")
        )

        viewModelScope.launch {
            try {
                aiClient.execute(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    endpoint = "models/gemini-2.5-flash:streamGenerateContent", // অথবা gemini-1.5-pro
                    headers = headers,
                    body = body
                ).collect { jsonString ->

                    // Gemini Stream Parsing Logic
                    val extractedText = try {
                        val json = JSONObject(jsonString)
                        // Gemini Response Path: candidates[0].content.parts[0].text
                        json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                    } catch (e: Exception) {
                        "" // যদি পার্সিং ফেইল করে (যেমন মেটাডেটা চাঙ্ক হলে)
                    }

                    _uiState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            responseText = currentState.responseText + jsonString
                        )
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
    val responseText: String = "",
    val error: String? = null
)