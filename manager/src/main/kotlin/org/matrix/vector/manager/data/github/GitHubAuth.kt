package org.matrix.vector.manager.data.github
import android.util.Log
import org.matrix.vector.manager.Constants

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.vector.manager.BuildConfig

/**
 * Optional GitHub sign-in, over the **device authorisation flow**.
 *
 * Why this flow and not a browser redirect: it needs no redirect URI, no custom scheme and no
 * callback into the app — which matters here because parasitically the manager has no package
 * identity of its own to register a scheme against. The user is shown a short code, types it at
 * github.com/login/device in any browser on any device, and this polls until it is approved.
 *
 * **No scopes are requested.** A token with an empty scope set can read public data and nothing
 * else, so one leaking out of the host process's data directory cannot be used to act as the user.
 * What it buys is the rate limit: 60 requests/hour for an anonymous client, 5000 signed in.
 *
 * Signing in is never required. Every surface that uses this renders without it, because a large
 * part of this project's users cannot reach github.com at all — see [SignInState.Unavailable].
 */
class GitHubAuth(context: Context, private val client: OkHttpClient) {

    private val prefs = context.getSharedPreferences("vector_github", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _state = MutableStateFlow<SignInState>(initialState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    /** False when no OAuth client id was compiled in, in which case the UI hides sign-in entirely. */
    val isConfigured: Boolean
        get() = BuildConfig.GITHUB_CLIENT_ID.isNotEmpty()

    private fun initialState(): SignInState {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return SignInState.SignedOut
        return SignInState.SignedIn(login = prefs.getString(KEY_LOGIN, null), token = stored)
    }

    /**
     * Starts the flow and polls to completion. Emits [SignInState.AwaitingUser] with the code the
     * user has to type, so the UI can show it and offer to copy it.
     */
    suspend fun signIn() {
        if (!isConfigured) {
            _state.value = SignInState.Unavailable("no client id")
            return
        }
        withContext(Dispatchers.IO) {
            val start =
                runCatching { requestDeviceCode() }
                    .getOrElse {
                        // Unreachable is the expected case for a lot of users, not an error worth
                        // shouting about.
                        Log.w(Constants.TAG, "auth: github device code request failed", it)
                        _state.value = SignInState.Unavailable(it.message ?: "unreachable")
                        return@withContext
                    }

            _state.value =
                SignInState.AwaitingUser(
                    userCode = start.userCode,
                    verificationUri = start.verificationUri,
                )

            val deadline = System.currentTimeMillis() + start.expiresIn * 1000L
            var interval = start.interval.coerceAtLeast(5)

            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                val poll = runCatching { pollForToken(start.deviceCode) }.getOrNull() ?: continue
                when {
                    poll.accessToken != null -> {
                        val login = runCatching { fetchLogin(poll.accessToken) }.getOrNull()
                        prefs
                            .edit()
                            .putString(KEY_TOKEN, poll.accessToken)
                            .putString(KEY_LOGIN, login)
                            .apply()
                        _state.value = SignInState.SignedIn(login, poll.accessToken)
                        return@withContext
                    }
                    poll.error == "authorization_pending" -> Unit
                    poll.error == "slow_down" -> interval += 5
                    poll.error == "access_denied" -> {
                        _state.value = SignInState.SignedOut
                        return@withContext
                    }
                    else -> {
                        Log.w(
                            Constants.TAG,
                            "auth: github device flow refused: ${poll.error ?: "unknown"}",
                        )
                        _state.value = SignInState.Unavailable(poll.error ?: "unknown")
                        return@withContext
                    }
                }
            }
            _state.value = SignInState.SignedOut
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
        _state.value = SignInState.SignedOut
    }

    fun cancel() {
        if (_state.value is SignInState.AwaitingUser) _state.value = SignInState.SignedOut
    }

    private fun requestDeviceCode(): DeviceCodeResponse {
        val body =
            FormBody.Builder()
                .add("client_id", BuildConfig.GITHUB_CLIENT_ID)
                // Deliberately empty: read-only access to public data is all this needs.
                .add("scope", "")
                .build()
        val request =
            Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(body)
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            return json.decodeFromString(text)
        }
    }

    private fun pollForToken(deviceCode: String): TokenResponse {
        val body =
            FormBody.Builder()
                .add("client_id", BuildConfig.GITHUB_CLIENT_ID)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
        val request =
            Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(body)
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            return json.decodeFromString(text)
        }
    }

    private fun fetchLogin(token: String): String? {
        val request =
            Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return json.decodeFromString<GhUser>(response.body.string()).login
        }
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
    }
}

sealed interface SignInState {
    data object SignedOut : SignInState

    data class AwaitingUser(val userCode: String, val verificationUri: String) : SignInState

    data class SignedIn(val login: String?, val token: String) : SignInState

    /**
     * No client id was compiled in, GitHub could not be reached, or it refused the grant. Not an
     * error state: every surface that offers sign-in renders without it.
     */
    data class Unavailable(val reason: String) : SignInState
}

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
)
