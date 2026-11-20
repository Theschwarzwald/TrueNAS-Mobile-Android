package com.example.truenasapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.truenasapp.data.LoginResult
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val store = Graph.store
    val scope = rememberCoroutineScope()
    val sessionMessage by Graph.session.loginMessage.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf(store.serverUrl ?: "") }
    var user by rememberSaveable { mutableStateOf(store.username ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var needOtp by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            val result = Graph.session.login(server, user, password, if (needOtp) otp else null)
            busy = false
            when (result) {
                LoginResult.Success -> Unit
                LoginResult.NeedsOtp -> needOtp = true
                is LoginResult.Error -> {
                    error = result.message
                    needOtp = false
                    otp = ""
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .readableWidth(480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(24.dp))
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(72.dp),
            )
            Text("TrueNAS", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Sign in to your NAS. You'll stay signed in until you log out.")

            sessionMessage?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.10 or http://nas.local") },
                singleLine = true, enabled = !needOtp && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username") }, singleLine = true, enabled = !needOtp && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true, enabled = !needOtp && !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (needOtp) {
                OutlinedTextField(
                    value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(8) },
                    label = { Text("Two-factor code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = { submit() },
                enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotBlank() &&
                    (!needOtp || otp.length >= 6),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (needOtp) "Verify" else "Sign in")
            }
        }
    }
}
