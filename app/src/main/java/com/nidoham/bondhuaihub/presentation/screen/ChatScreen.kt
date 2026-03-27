package com.nidoham.bondhuaihub.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nidoham.bondhuaihub.presentation.viewmodel.MainViewModel

@Composable
fun ChatScreen(
    viewModel: MainViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.responseText, uiState.thoughtText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Thought block
            AnimatedVisibility(visible = uiState.thoughtText.isNotEmpty()) {
                Text(
                    text = uiState.thoughtText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                )
            }

            // Response block
            if (uiState.responseText.isNotEmpty()) {
                Text(
                    text = uiState.responseText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!uiState.isLoading) {
                Text(
                    text = "Ask me anything...",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            // Loading indicator inline
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            }

            // Error
            uiState.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a prompt...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )

            Button(
                onClick = {
                    viewModel.sendPrompt(inputText.trim())
                    inputText = ""
                },
                enabled = inputText.isNotBlank() && !uiState.isLoading
            ) {
                Text("Send")
            }
        }
    }
}