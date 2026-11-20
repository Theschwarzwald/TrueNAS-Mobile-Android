package com.example.truenasapp.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.truenasapp.Graph
import com.example.truenasapp.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.truenasapp.lock.AppLockManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LockScreen() {
    val activity = LocalContext.current as FragmentActivity
    val lock = Graph.lock
    val scope = rememberCoroutineScope()
    val canUseBiometrics = remember { lock.biometricEnabled && biometricAvailable(activity) }

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Back button just sends the app to the background, it never skips the lock.
    BackHandler { activity.moveTaskToBack(true) }

    // Pop the fingerprint/face prompt straight away, like Nextcloud does.
    LaunchedEffect(Unit) {
        if (canUseBiometrics) {
            delay(300)
            showBiometricPrompt(activity) { lock.unlockWithBiometrics() }
        }
    }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true
        scope.launch {
            when (val r = lock.tryPin(pin)) {
                AppLockManager.UnlockResult.Ok -> Unit
                AppLockManager.UnlockResult.Wrong -> error = "Wrong passcode"
                is AppLockManager.UnlockResult.LockedOut -> error = "Too many attempts. Try again in ${r.seconds}s."
            }
            pin = ""
            busy = false
        }
    }

    // A Material3 Surface swallows touches, so nothing underneath can be tapped.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(32.dp)
                .readableWidth(420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.size(16.dp))
            Text("TrueNAS is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pin = it },
                label = { Text("Passcode") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.size(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(16.dp))
            Button(onClick = { submit() }, enabled = pin.length >= 4 && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock")
            }
            if (canUseBiometrics) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { showBiometricPrompt(activity) { lock.unlockWithBiometrics() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use biometrics")
                }
            }
        }
    }
}

fun biometricAvailable(context: Context): Boolean =
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun showBiometricPrompt(activity: FragmentActivity, title: String = "Unlock TrueNAS", onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Confirm it's you")
            .setNegativeButtonText("Use passcode")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
    )
}
