package com.podclatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.ConnectionTestResult
import com.podclatch.data.repo.SaveResult
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.LocalHostClassifier
import com.podclatch.domain.LocalNetworkAccess
import com.podclatch.domain.LocalNetworkFlow
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.UrlResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Holds the settings for the root switch; null until they are first read, so nothing flashes. */
@HiltViewModel
class MainViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val settings: StateFlow<Settings?> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

data class ConnectionUiState(
    val url: String = "",
    val token: String = "",
    val showToken: Boolean = false,
    val testing: Boolean = false,
    val message: ConnectionTestResult? = null,
    /** The exact (url, token) pair that last passed "Test connection". */
    val verified: Pair<String, String>? = null,
    val confirmSwitch: Boolean = false,
    val saving: Boolean = false,
    /** The last Save went through; shown until the next edit. */
    val saved: Boolean = false,
) {
    val canTest: Boolean get() = url.isNotBlank() && token.isNotBlank() && !testing && !saving

    /** Save is enabled only after a successful test of exactly the values now in the fields. */
    val canSave: Boolean get() = verified == (url to token) && !testing && !saving
}

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connection: ConnectionRepository,
    private val settings: SettingsRepository,
    tokenRejection: TokenRejection,
    private val urlValidator: ServerUrlValidator,
    access: LocalNetworkAccess,
    classifier: LocalHostClassifier,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    /** The `ACCESS_LOCAL_NETWORK` flow of Test connection (dialog, system request, refusal). */
    val localNetwork = LocalNetworkFlow(viewModelScope, access, classifier, proceed = ::runTest)

    /** Shows the "rejected" notice above the fields. */
    val tokenRejected: StateFlow<Boolean> = tokenRejection.rejected

    init {
        // Start from the saved URL with an empty token, and again whenever the app stops being configured
        // (the token was rejected). The stored token is never displayed.
        viewModelScope.launch {
            var first = true
            settings.settings.map { it.isConfigured }.distinctUntilChanged().collect { configured ->
                if (first || !configured) resetForm()
                first = false
            }
        }
    }

    // An edit makes the last result stale, so it is cleared along with the Save enablement.
    fun onUrlChange(value: String) {
        localNetwork.reset()
        _state.update { it.copy(url = value, message = null, saved = false) }
    }

    fun onTokenChange(value: String) {
        localNetwork.reset()
        _state.update { it.copy(token = value, message = null, saved = false) }
    }

    fun toggleShowToken() = _state.update { it.copy(showToken = !it.showToken) }

    /** Test connection: asks for local network access first when the address needs it, then tests. */
    fun testConnection() {
        val current = _state.value
        if (!current.canTest) return
        localNetwork.reset()
        _state.update { it.copy(testing = true, message = null, saved = false) }
        val valid = urlValidator.validate(current.url) as? UrlResult.Valid
        val host = valid?.url?.toHttpUrlOrNull()?.host
        localNetwork.begin(host, valid?.displayHost) { asked ->
            if (asked) _state.update { it.copy(testing = false) }
        }
    }

    private fun runTest() {
        val current = _state.value
        _state.update { it.copy(testing = true, message = null, saved = false) }
        viewModelScope.launch {
            val result = connection.test(current.url, current.token)
            _state.update {
                it.copy(
                    testing = false,
                    message = result,
                    verified = if (result is ConnectionTestResult.Success) current.url to current.token else null,
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            if (connection.requiresWipe(current.url)) {
                _state.update { it.copy(confirmSwitch = true) }
            } else {
                doSave(current)
            }
        }
    }

    fun confirmSwitch() {
        val current = _state.value
        _state.update { it.copy(confirmSwitch = false) }
        if (current.canSave) viewModelScope.launch { doSave(current) }
    }

    fun dismissSwitch() = _state.update { it.copy(confirmSwitch = false) }

    private suspend fun doSave(current: ConnectionUiState) {
        _state.update { it.copy(saving = true) }
        val result = connection.save(current.url, current.token)
        if (result == SaveResult.Saved) {
            _state.update { it.copy(saving = false, token = "", verified = null, message = null, saved = true) }
        } else {
            _state.update { it.copy(saving = false, verified = null) }
        }
    }

    private fun resetForm() {
        viewModelScope.launch {
            val savedUrl = settings.current().serverUrl
            _state.value = ConnectionUiState(url = savedUrl)
        }
    }
}
