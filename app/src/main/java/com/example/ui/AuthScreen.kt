package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.ui.theme.BorderSteel
import com.example.ui.theme.ElevatedGunmetal
import com.example.ui.theme.IceCyan
import com.example.ui.theme.LocalUiMetrics
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RtxEmerald
import com.example.ui.theme.TacticalAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThreatCrimson
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Silent startup auto-sign-in using exclusively GetGoogleIdOption (one option per GetCredentialRequest).
 */
fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    scope.launch {
        try {
            val activityCtx = context.findActivity() ?: context
            val cred = credentialManager.getCredential(activityCtx, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (_: Exception) {
            onUnauthenticated()
        }
    }
}

/**
 * Interactive Google Sign-In using exclusively GetSignInWithGoogleOption (one option per GetCredentialRequest).
 */
fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onAuthError("Конфигурация Google Sign-In не найдена (default_web_client_id).")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()
    scope.launch {
        try {
            val activityCtx = context.findActivity()
                ?: throw IllegalStateException("Activity context недоступен")
            val cred = credentialManager.getCredential(activityCtx, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                onAuthSuccess()
            } else {
                onAuthError("Неожиданный тип учётных данных Google.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Не удалось выполнить вход через Google.")
        }
    }
}

/**
 * Safe sign-out wrapped in NonCancellable so UI unmount never cancels teardown.
 */
fun signOutWithGoogle(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onBeforeSignOut: () -> Unit = {},
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                onBeforeSignOut()
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val m = LocalUiMetrics.current

    var isCheckingAuto by remember { mutableStateOf(true) }
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isCheckingAuto = false
                onAuthSuccess()
            },
            onUnauthenticated = {
                isCheckingAuto = false
            },
            scope = scope
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF060A12), Color(0xFF0C1524), Color(0xFF080E18))
                )
            )
            .safeDrawingPadding()
            .padding(horizontal = m.s(20f), vertical = m.s(12f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 820.dp),
            horizontalArrangement = Arrangement.spacedBy(m.s(18f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Vanguard RTX Tactical Branding & Online Features
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.spacedBy(m.s(10f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    Box(
                        modifier = Modifier
                            .size(m.s(36f))
                            .clip(CutCornerShape(m.s(8f)))
                            .background(RtxEmerald.copy(alpha = 0.2f))
                            .border(1.2.dp, RtxEmerald, CutCornerShape(m.s(8f))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = RtxEmerald,
                            modifier = Modifier.size(m.s(20f))
                        )
                    }
                    Column {
                        Text(
                            text = "VANGUARD RTX",
                            fontSize = m.sp(20f),
                            fontWeight = FontWeight.Black,
                            letterSpacing = m.sp(2f),
                            color = TextPrimary
                        )
                        Text(
                            text = "CS2 3D ТАКТИЧЕСКИЙ ШУТЕР • ОНЛАЙН",
                            fontSize = m.sp(9.5f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = m.sp(1.1f),
                            color = RtxEmerald
                        )
                    }
                }

                Spacer(modifier = Modifier.height(m.s(2f)))

                AuthFeatureRow(
                    icon = Icons.Default.Tag,
                    accent = TacticalAmber,
                    title = "Уникальный 6-значный ID игрока",
                    subtitle = "Личный цифровой ID только для реальных игроков (без ботов)"
                )
                AuthFeatureRow(
                    icon = Icons.Default.PersonAdd,
                    accent = IceCyan,
                    title = "Друзья и приглашения в лобби",
                    subtitle = "Находите друзей по 6-значному ID и зовите в матч одним нажатием"
                )
                AuthFeatureRow(
                    icon = Icons.Default.Groups,
                    accent = RtxEmerald,
                    title = "Мультиплеер 1 на 1 и командой",
                    subtitle = "Свободный выбор стороны T / CT и настройка ботов хостом"
                )
            }

            // Right: Mandatory Google Sign-In Card
            Surface(
                color = Color(0xE8101827),
                shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
                border = BorderStroke(1.2.dp, RtxEmerald.copy(alpha = 0.55f)),
                modifier = Modifier.weight(0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(m.s(18f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(m.s(12f))
                ) {
                    Text(
                        text = "ВХОД В АККАУНТ",
                        fontSize = m.sp(15f),
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = m.sp(1.2f)
                    )
                    Text(
                        text = "Войдите через Google, чтобы получить постоянный ID игрока, добавлять друзей и играть по сети.",
                        fontSize = m.sp(10.5f),
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(m.s(8f)))
                                .background(ThreatCrimson.copy(alpha = 0.16f))
                                .border(1.dp, ThreatCrimson.copy(alpha = 0.55f), RoundedCornerShape(m.s(8f)))
                                .padding(horizontal = m.s(10f), vertical = m.s(7f))
                        ) {
                            Text(
                                text = errorMessage!!,
                                fontSize = m.sp(10f),
                                color = Color(0xFFFFB4B4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Button(
                        onClick = {
                            errorMessage = null
                            isSigningIn = true
                            onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = {
                                    isSigningIn = false
                                    onAuthSuccess()
                                },
                                onAuthError = { msg ->
                                    isSigningIn = false
                                    errorMessage = msg
                                },
                                scope = scope,
                                onAuthCancelled = {
                                    isSigningIn = false
                                }
                            )
                        },
                        enabled = !isSigningIn && !isCheckingAuto,
                        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RtxEmerald,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = m.s(18f), vertical = m.s(12f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(m.s(48f).coerceAtLeast(48.dp))
                            .testTag("google_sign_in_button")
                    ) {
                        if (isSigningIn || isCheckingAuto) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(m.s(20f)),
                                color = ObsidianVoid,
                                strokeWidth = 2.4.dp
                            )
                            Spacer(modifier = Modifier.width(m.s(8f)))
                            Text(
                                text = if (isCheckingAuto) "ПРОВЕРКА..." else "ВХОД...",
                                fontSize = m.sp(12f),
                                fontWeight = FontWeight.Black
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(m.s(19f))
                            )
                            Spacer(modifier = Modifier.width(m.s(8f)))
                            Text(
                                text = "ВОЙТИ ЧЕРЕЗ GOOGLE",
                                fontSize = m.sp(12.5f),
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthFeatureRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String
) {
    val m = LocalUiMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.s(8f)))
            .background(ElevatedGunmetal.copy(alpha = 0.65f))
            .border(1.dp, BorderSteel, RoundedCornerShape(m.s(8f)))
            .padding(horizontal = m.s(10f), vertical = m.s(8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.s(10f))
    ) {
        Box(
            modifier = Modifier
                .size(m.s(30f))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(m.s(16f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(m.s(1f))) {
            Text(
                text = title,
                fontSize = m.sp(11f),
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = m.sp(9.5f),
                color = TextSecondary
            )
        }
    }
}
