package io.github.ambientcore.resonance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ServerSetupDialog(model: MusicViewModel) {
    val server by model.server.collectAsStateWithLifecycle()
    val setup by model.serverSetup.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(server.url) }
    var username by rememberSaveable { mutableStateOf(server.username) }
    // Passwords are intentionally not placed in saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var http by rememberSaveable { mutableStateOf(server.allowHttp) }
    AlertDialog(onDismissRequest = model::closeServerSetup,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            val hideKeyboard = rememberKeyboardDismiss()
            Row(Modifier.pointerInput(hideKeyboard) { detectTapGestures(onTap = { hideKeyboard() }) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Navidrome server", Modifier.weight(1f))
                IconButton(onClick = { hideKeyboard() }) { Icon(Icons.Rounded.KeyboardHide, "Hide keyboard") }
            }
        },
        text = {
            val hideKeyboard = rememberKeyboardDismiss()
            val done = KeyboardActions(onDone = { hideKeyboard() })
            Column(Modifier.verticalScroll(rememberScrollState())
                .pointerInput(hideKeyboard) { detectTapGestures(onTap = { hideKeyboard() }) },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(url, { url = it; model.invalidateServerTest() }, singleLine = true,
                label = { Text("Server URL") }, placeholder = { Text("https://nas.example.ts.net") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = done,
                modifier = Modifier.testTag("server-url"), enabled = !setup.running)
            OutlinedTextField(username, { username = it; model.invalidateServerTest() }, singleLine = true,
                label = { Text("Username") }, modifier = Modifier.testTag("server-username"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = done,
                enabled = !setup.running)
            OutlinedTextField(password, { password = it; model.invalidateServerTest() }, singleLine = true,
                label = { Text(if (server.configured) "Password (blank keeps saved login)" else "Password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (showPassword) "Hide password" else "Show password")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = done,
                modifier = Modifier.testTag("server-password"), enabled = !setup.running)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(http, { http = it; hideKeyboard(); model.invalidateServerTest() }, enabled = !setup.running,
                    modifier = Modifier.testTag("server-http"))
                Text("Allow HTTP over trusted LAN / Tailscale", fontSize = 12.sp)
            }
            Text("Connect Tailscale on both the NAS and R4 for access away from home. HTTP is not encrypted by this app; only use it inside a trusted network or VPN.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Text("Switching sources changes browsing, not the playing track. Server audio streams in its original format; no full-library audio download.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            TextButton(onClick = { hideKeyboard(); model.testServer(url, username, password, http) }, enabled = !setup.running) {
                Text("Test connection")
            }
            if (setup.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (setup.message.isNotBlank()) Text(setup.message, fontSize = 12.sp,
                color = if (setup.tested) Lime else Muted, modifier = Modifier.testTag("server-test-result"))
        } },
        confirmButton = {
            val hideKeyboard = rememberKeyboardDismiss()
            TextButton(onClick = { hideKeyboard(); model.saveServer() }, enabled = setup.tested && !setup.running) { Text("Save and connect") }
        },
        dismissButton = {
            val hideKeyboard = rememberKeyboardDismiss()
            TextButton(onClick = { hideKeyboard(); model.closeServerSetup() }, enabled = !setup.running) { Text("Cancel") }
        })
}

@Composable
private fun rememberKeyboardDismiss(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) { { focus.clearFocus(); keyboard?.hide() } }
}
