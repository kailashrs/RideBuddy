package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.navigation.NavigationApi
import com.spaceboy.ridebuddy.core.security.NavigationApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation-key state for the settings screen. Only the masked key is held here; the key
 * itself stays in the secure store.
 */
data class NavigationKeyUiState(
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** A key is already applied to this process; the SDK takes a replacement only on restart. */
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * The rider's Navigation SDK key: stored encrypted, applied to the SDK at most once per process.
 */
class NavigationApiKey(
    private val store: NavigationApiKeyStore,
    scope: CoroutineScope,
    private val applyToSdk: (String) -> Unit = NavigationApi::setApiKey,
) {
    private val mutableState = MutableStateFlow(NavigationKeyUiState(isLoading = true))
    val state: StateFlow<NavigationKeyUiState> = mutableState.asStateFlow()

    /** The key the SDK is using in this process, once one has been applied. */
    private var appliedKey: String? = null

    val isApplied: Boolean @Synchronized get() = appliedKey != null

    init {
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { store.load() }.getOrNull()
            mutableState.value = stored?.let(::apply) ?: NavigationKeyUiState()
        }
    }

    suspend fun awaitLoaded(): NavigationKeyUiState = state.first { !it.isLoading }

    /** Returns the message to show the rider. */
    suspend fun save(value: String): String? {
        val key = value.trim()
        validationError(key)?.let { error ->
            mutableState.update { it.copy(errorMessage = error) }
            return null
        }
        return operation {
            val applied = apply(key)
            if (applied.errorMessage == null) store.save(key)
            mutableState.value = applied
            if (applied.errorMessage == null) "Navigation API key saved" else null
        }
    }

    suspend fun remove(): String? = operation {
        store.clear()
        mutableState.value = NavigationKeyUiState(restartRequired = isApplied)
        "Navigation API key removed"
    }

    suspend fun test(): String? = operation {
        val key = store.load() ?: return@operation "Add an API key before testing"
        val result = apply(key)
        mutableState.value = result
        when {
            result.errorMessage != null -> result.errorMessage
            result.restartRequired -> "Restart the app to test the replacement key"
            else -> "Key configured. Start a route to verify access."
        }
    }

    /** One operation at a time; the flag also drives the settings screen's progress state. */
    private suspend fun operation(block: suspend () -> String?): String? {
        if (state.value.isLoading || state.value.isSaving) return null
        mutableState.update { it.copy(isSaving = true, errorMessage = null) }
        return try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.message ?: "Navigation setup failed") }
            null
        } finally {
            mutableState.update { it.copy(isSaving = false) }
        }
    }

    @Synchronized
    private fun apply(key: String): NavigationKeyUiState {
        val masked = mask(key)
        val current = appliedKey
        if (current != null) return NavigationKeyUiState(true, masked, restartRequired = current != key)
        return runCatching { applyToSdk(key) }.fold(
            onSuccess = {
                appliedKey = key
                NavigationKeyUiState(isConfigured = true, maskedKey = masked)
            },
            onFailure = { error ->
                NavigationKeyUiState(maskedKey = masked, errorMessage = error.message ?: "Navigation SDK rejected the API key")
            },
        )
    }

    companion object {
        /** Catches an empty field, a truncated paste, or stray whitespace; the SDK judges the rest. */
        fun validationError(key: String): String? = when {
            key.isEmpty() -> "Enter a Google Navigation API key"
            key.length < 20 -> "The API key looks too short"
            key.any(Char::isWhitespace) -> "The API key cannot contain spaces"
            else -> null
        }

        /** Enough to tell which key is stored without putting it on screen. */
        fun mask(key: String): String = "•••• ${key.takeLast(4)}"
    }
}
