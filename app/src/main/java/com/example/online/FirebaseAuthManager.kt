package com.example.online

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Result of a Google Sign-In attempt via Jetpack Credential Manager + Firebase Auth.
 */
sealed interface GoogleSignInResult {
    data class Success(val user: FirebaseUser) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Error(val message: String, val cause: Throwable? = null) : GoogleSignInResult
}

/**
 * Reactive authentication session state backed by Firebase Auth persistence.
 */
sealed interface AuthSessionState {
    data object Checking : AuthSessionState
    data object Unauthenticated : AuthSessionState
    data class Authenticated(val user: FirebaseUser) : AuthSessionState
}

/**
 * Centralized Firebase Authentication manager handling the mandatory Google Sign-In flow
 * via Jetpack Credential Manager and persisting user sessions across application restarts.
 *
 * Security & Lifecycle invariants:
 * - Relies on Firebase Auth's built-in persistent session storage (`auth.currentUser`) and
 *   exposes reactive updates via `FirebaseAuth.AuthStateListener`.
 * - Startup silent sign-in uses exclusively `GetGoogleIdOption` (one option per request).
 * - Interactive sign-in uses exclusively `GetSignInWithGoogleOption` (one option per request).
 * - Never falls back to anonymous or email/password authentication.
 * - Sign-out runs inside `NonCancellable` to guarantee Credential Manager and Firebase teardown.
 */
class FirebaseAuthManager(
    context: Context,
    private val auth: FirebaseAuth = Firebase.auth,
    private val credentialManager: CredentialManager = CredentialManager.create(context.applicationContext)
) {
    private val appContext: Context = context.applicationContext

    /**
     * Returns the currently persisted signed-in [FirebaseUser], or `null` if unauthenticated.
     */
    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /**
     * Returns `true` if a persisted Firebase Auth user session is currently active.
     */
    val isSignedIn: Boolean
        get() = auth.currentUser != null

    /**
     * Observes persisted Firebase Auth user changes in real time using [FirebaseAuth.AuthStateListener].
     * Immediately emits the persisted session on subscription so returning users stay signed in.
     */
    fun observeAuthState(): Flow<AuthSessionState> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            trySend(
                if (user != null) AuthSessionState.Authenticated(user)
                else AuthSessionState.Unauthenticated
            )
        }
        auth.addAuthStateListener(listener)
        awaitClose {
            auth.removeAuthStateListener(listener)
        }
    }.distinctUntilChanged { old, new ->
        when {
            old is AuthSessionState.Authenticated && new is AuthSessionState.Authenticated ->
                old.user.uid == new.user.uid
            else -> old == new
        }
    }

    /**
     * Checks for an existing persisted Firebase Auth session first. If none is stored locally,
     * attempts silent Credential Manager restoration using exclusively [GetGoogleIdOption]
     * (one option per [GetCredentialRequest]) for previously authorized Google accounts.
     */
    suspend fun restoreSessionOrAutoSignIn(uiContext: Context): GoogleSignInResult {
        auth.currentUser?.let { persistedUser ->
            return GoogleSignInResult.Success(persistedUser)
        }

        val clientId = resolveWebClientId()
            ?: return GoogleSignInResult.Error(
                "Конфигурация Google Sign-In не найдена (default_web_client_id)."
            )

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val requestContext = uiContext.findActivity() ?: uiContext
            val credential = credentialManager.getCredential(requestContext, request).credential
            exchangeGoogleCredentialForFirebaseUser(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Silent Google auto-sign-in dismissed: ${e.message}", e)
            GoogleSignInResult.Cancelled
        } catch (e: Exception) {
            Log.d(TAG, "No persisted or silent Google credential available at startup: ${e.message}")
            GoogleSignInResult.Error(
                message = e.localizedMessage ?: "Автоматический вход недоступен.",
                cause = e
            )
        }
    }

    /**
     * Launches the mandatory interactive Google Sign-In bottom sheet using exclusively
     * [GetSignInWithGoogleOption] (one option per [GetCredentialRequest]).
     * On success, Firebase Auth automatically persists the user session on-device.
     */
    suspend fun signInWithGoogle(uiContext: Context): GoogleSignInResult {
        val clientId = resolveWebClientId()
            ?: return GoogleSignInResult.Error(
                "Конфигурация Google Sign-In не найдена (default_web_client_id)."
            )

        val activity = uiContext.findActivity()
            ?: return GoogleSignInResult.Error("Activity context недоступен для входа через Google.")

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            exchangeGoogleCredentialForFirebaseUser(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            GoogleSignInResult.Cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            GoogleSignInResult.Error(
                message = e.localizedMessage ?: "Не удалось выполнить вход через Google.",
                cause = e
            )
        }
    }

    /**
     * Signs out of Firebase Auth and clears Credential Manager state inside [NonCancellable]
     * so UI unmount on auth state transition never interrupts teardown.
     */
    suspend fun signOut(
        onBeforeSignOut: (suspend () -> Unit)? = null,
        onSignOutComplete: () -> Unit = {}
    ) {
        withContext(NonCancellable) {
            try {
                onBeforeSignOut?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Pre-sign-out hook failed: ${e.message}")
            }
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                auth.signOut()
                onSignOutComplete()
            }
        }
    }

    private suspend fun exchangeGoogleCredentialForFirebaseUser(
        credential: androidx.credentials.Credential
    ): GoogleSignInResult {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authResult = auth.signInWithCredential(
                GoogleAuthProvider.getCredential(googleIdToken, null)
            ).await()
            val signedInUser = authResult.user ?: auth.currentUser
            return if (signedInUser != null) {
                GoogleSignInResult.Success(signedInUser)
            } else {
                GoogleSignInResult.Error("Сессия Firebase не создана после входа через Google.")
            }
        }
        return GoogleSignInResult.Error("Неожиданный тип учётных данных Google.")
    }

    private fun resolveWebClientId(): String? = try {
        appContext.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    companion object {
        private const val TAG = "Auth"
    }
}
