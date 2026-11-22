package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.online.AuthActionResult
import com.example.online.SupabaseAuthManager
import com.example.online.SupabaseConfig
import com.example.online.SupabaseSession
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
import kotlinx.coroutines.launch

private enum class AuthMode { LOGIN, REGISTER }

@Composable
fun AuthScreen(
    authManager: SupabaseAuthManager,
    onAuthSuccess: (SupabaseSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    val m = LocalUiMetrics.current

    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var loginInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember {
        mutableStateOf(
            if (authManager.isConfigured) null else SupabaseConfig.MISSING_CONFIG_MESSAGE
        )
    }

    fun submit() {
        if (isSubmitting) return
        errorMessage = null
        isSubmitting = true
        val currentMode = mode
        val login = loginInput
        val pass = passwordInput
        val confirm = confirmPasswordInput
        scope.launch {
            val result = if (currentMode == AuthMode.REGISTER) {
                authManager.register(login, pass, confirm)
            } else {
                authManager.login(login, pass)
            }
            isSubmitting = false
            when (result) {
                is AuthActionResult.Success -> onAuthSuccess(result.session)
                is AuthActionResult.Error -> errorMessage = result.message
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = RtxEmerald,
        unfocusedBorderColor = BorderSteel,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = RtxEmerald,
        focusedLeadingIconColor = RtxEmerald,
        unfocusedLeadingIconColor = TextSecondary,
        focusedTrailingIconColor = TextPrimary,
        unfocusedTrailingIconColor = TextSecondary,
        focusedLabelColor = RtxEmerald,
        unfocusedLabelColor = TextSecondary
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF060A12), Color(0xFF0C1524), Color(0xFF080E18))
                )
            )
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = m.s(18f), vertical = m.s(10f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 860.dp),
            horizontalArrangement = Arrangement.spacedBy(m.s(18f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Vanguard RTX Tactical Branding & Cloud Features
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(m.s(9f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.s(9f))
                ) {
                    Box(
                        modifier = Modifier
                            .size(m.s(36f))
                            .clip(CutCornerShape(m.s(8f)))
                            .background(RtxEmerald.copy(alpha = 0.20f))
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
                            text = "ТАКТИЧЕСКИЙ ШУТЕР • ОНЛАЙН",
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
                    title = "Друзья по 6-значному ID",
                    subtitle = "Находите друзей через интернет без Wi-Fi и ввода IP-адресов"
                )
                AuthFeatureRow(
                    icon = Icons.Default.Groups,
                    accent = IceCyan,
                    title = "Лобби 1 на 1 и приглашения",
                    subtitle = "Добавляйте друга прямо в лобби и запускайте дуэль T / CT"
                )
                AuthFeatureRow(
                    icon = Icons.Default.Face,
                    accent = RtxEmerald,
                    title = "Кастомизация профиля",
                    subtitle = "Меняйте имя и аватарку (фото из галереи или тактические иконки)"
                )
            }

            // Right: Supabase Login / Register Card
            Surface(
                color = Color(0xEA101827),
                shape = CutCornerShape(topStart = m.s(16f), bottomEnd = m.s(16f)),
                border = BorderStroke(
                    width = 1.2.dp,
                    color = if (mode == AuthMode.LOGIN) RtxEmerald.copy(alpha = 0.55f) else IceCyan.copy(alpha = 0.58f)
                ),
                modifier = Modifier.weight(1.05f)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = m.s(16f), vertical = m.s(13f)),
                    verticalArrangement = Arrangement.spacedBy(m.s(8f))
                ) {
                    // Mode Switcher: ВОЙТИ / РЕГИСТРАЦИЯ
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.s(8f)))
                            .background(Color(0xB8070C14))
                            .border(1.dp, BorderSteel, RoundedCornerShape(m.s(8f)))
                            .padding(m.s(3f)),
                        horizontalArrangement = Arrangement.spacedBy(m.s(4f))
                    ) {
                        AuthModeTab(
                            label = "ВОЙТИ",
                            selected = mode == AuthMode.LOGIN,
                            accent = RtxEmerald,
                            onClick = {
                                mode = AuthMode.LOGIN
                                if (authManager.isConfigured) errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            tag = "auth_mode_login"
                        )
                        AuthModeTab(
                            label = "РЕГИСТРАЦИЯ",
                            selected = mode == AuthMode.REGISTER,
                            accent = IceCyan,
                            onClick = {
                                mode = AuthMode.REGISTER
                                if (authManager.isConfigured) errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            tag = "auth_mode_register"
                        )
                    }

                    OutlinedTextField(
                        value = loginInput,
                        onValueChange = { raw ->
                            loginInput = raw.take(20)
                            if (authManager.isConfigured) errorMessage = null
                        },
                        label = { Text("Логин (3–20 символов)", fontSize = m.sp(10f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(m.s(17f)))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_login_input")
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { raw ->
                            passwordInput = raw.take(64)
                            if (authManager.isConfigured) errorMessage = null
                        },
                        label = { Text("Пароль (от 6 символов)", fontSize = m.sp(10f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(m.s(17f)))
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                modifier = Modifier.testTag("auth_toggle_password_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль",
                                    modifier = Modifier.size(m.s(18f))
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (mode == AuthMode.REGISTER) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (mode == AuthMode.LOGIN) submit() }
                        ),
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input")
                    )

                    AnimatedVisibility(visible = mode == AuthMode.REGISTER) {
                        OutlinedTextField(
                            value = confirmPasswordInput,
                            onValueChange = { raw ->
                                confirmPasswordInput = raw.take(64)
                                if (authManager.isConfigured) errorMessage = null
                            },
                            label = { Text("Повторите пароль", fontSize = m.sp(10f)) },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(m.s(17f)))
                            },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            colors = fieldColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_confirm_password_input")
                        )
                    }

                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(m.s(7f)))
                                .background(ThreatCrimson.copy(alpha = 0.17f))
                                .border(1.dp, ThreatCrimson.copy(alpha = 0.60f), RoundedCornerShape(m.s(7f)))
                                .padding(horizontal = m.s(10f), vertical = m.s(6f))
                        ) {
                            Text(
                                text = errorMessage!!,
                                fontSize = m.sp(9.5f),
                                color = Color(0xFFFFB4B4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    val ctaAccent = if (mode == AuthMode.LOGIN) RtxEmerald else IceCyan
                    Button(
                        onClick = { submit() },
                        enabled = !isSubmitting,
                        shape = CutCornerShape(topStart = m.s(10f), bottomEnd = m.s(10f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ctaAccent,
                            contentColor = ObsidianVoid
                        ),
                        contentPadding = PaddingValues(horizontal = m.s(16f), vertical = m.s(10f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(m.s(46f).coerceAtLeast(48.dp))
                            .testTag("auth_submit_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(m.s(18f)),
                                color = ObsidianVoid,
                                strokeWidth = 2.4.dp
                            )
                            Spacer(modifier = Modifier.width(m.s(8f)))
                            Text(
                                text = if (mode == AuthMode.REGISTER) "СОЗДАНИЕ..." else "ВХОД...",
                                fontSize = m.sp(12f),
                                fontWeight = FontWeight.Black
                            )
                        } else {
                            Icon(
                                imageVector = if (mode == AuthMode.REGISTER) Icons.Default.PersonAdd else Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(m.s(18f))
                            )
                            Spacer(modifier = Modifier.width(m.s(8f)))
                            Text(
                                text = if (mode == AuthMode.REGISTER) "ЗАРЕГИСТРИРОВАТЬСЯ" else "ВОЙТИ В АККАУНТ",
                                fontSize = m.sp(12f),
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
private fun AuthModeTab(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String
) {
    val m = LocalUiMetrics.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(m.s(6f)))
            .background(if (selected) accent.copy(alpha = 0.24f) else Color.Transparent)
            .border(
                width = if (selected) 1.2.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(m.s(6f))
            )
            .clickable(onClick = onClick)
            .padding(vertical = m.s(8f))
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = m.sp(10.5f),
            fontWeight = FontWeight.Black,
            letterSpacing = m.sp(0.9f),
            color = if (selected) accent else TextSecondary
        )
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
            .padding(horizontal = m.s(10f), vertical = m.s(7f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.s(9f))
    ) {
        Box(
            modifier = Modifier
                .size(m.s(28f))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(m.s(15f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(m.s(1f))) {
            Text(
                text = title,
                fontSize = m.sp(10.5f),
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = m.sp(9.2f),
                color = TextSecondary
            )
        }
    }
}
