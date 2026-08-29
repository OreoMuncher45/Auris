package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCanvasAuthScreen(
    onNavigateUp: () -> Unit
) {
    val currentToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify Canvas Setup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "To enable Spotify Canvas integration, you need to provide your 'sp_dc' cookie from Spotify.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "1. Open your browser and log into open.spotify.com\n" +
                       "2. Open Developer Tools (F12) -> Application -> Cookies\n" +
                       "3. Find the cookie named 'sp_dc' and copy its value\n" +
                       "4. Paste it below",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("sp_dc token") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                singleLine = true
            )

            Button(
                onClick = { 
                    AppSettings.setSpotifySpdcToken(tokenInput.trim())
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
