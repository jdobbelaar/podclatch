package com.podclatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podclatch.data.db.CacheStore
import com.podclatch.data.repo.ConnectionRepository
import com.podclatch.data.repo.ConnectionTestResult
import com.podclatch.data.repo.SaveResult
import com.podclatch.data.repo.Settings
import com.podclatch.data.repo.SettingsRepository
import com.podclatch.domain.ServerUrlValidator
import com.podclatch.domain.SyncEngine
import com.podclatch.domain.SyncState
import com.podclatch.domain.SyncTrigger
import com.podclatch.domain.TokenRejection
import com.podclatch.domain.UrlResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Opened from the placeholder home, so Cancel and system Back go back there. */
    val fromHome: Boolean = false,
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
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    /** Shows the "rejected" notice above the fields. */
    val tokenRejected: StateFlow<Boolean> = tokenRejection.rejected

    init {
        // Whenever the app is not configured (first start, or the token was rejected), start from the
        // saved URL with an empty token. The stored token is never displayed.
        viewModelScope.launch {
            settings.settings.map { it.isConfigured }.distinctUntilChanged().collect { configured ->
                if (!configured) resetForm(fromHome = false)
            }
        }
    }

    fun openFromHome() = resetForm(fromHome = true)

    fun cancel() = _state.update { it.copy(fromHome = false) }

    // An edit makes the last result stale, so it is cleared along with the Save enablement.
    fun onUrlChange(value: String) = _state.update { it.copy(url = value, message = null) }

    fun onTokenChange(value: String) = _state.update { it.copy(token = value, message = null) }

    fun toggleShowToken() = _state.update { it.copy(showToken = !it.showToken) }

    fun testConnection() {
        val current = _state.value
        if (!current.canTest) return
        _state.update { it.copy(testing = true, message = null) }
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
            _state.update { it.copy(saving = false, token = "", verified = null, message = null, fromHome = false) }
        } else {
            _state.update { it.copy(saving = false, verified = null) }
        }
    }

    private fun resetForm(fromHome: Boolean) {
        viewModelScope.launch {
            val savedUrl = settings.current().serverUrl
            _state.value = ConnectionUiState(url = savedUrl, fromHome = fromHome)
        }
    }
}

data class HomeUiState(
    val host: String = "",
    val feeds: Int = 0,
    val episodes: Int = 0,
    val revision: Long = 0,
    val sync: SyncState = SyncState(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val engine: SyncEngine,
    settings: SettingsRepository,
    store: CacheStore,
    validator: ServerUrlValidator,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(
        settings.settings,
        store.feedCount(),
        store.episodeCount(),
        engine.state,
    ) { s, feeds, episodes, sync ->
        val host = (validator.validate(s.serverUrl) as? UrlResult.Valid)?.displayHost ?: s.serverUrl
        HomeUiState(host, feeds, episodes, s.lastSyncRev, sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun syncNow() {
        viewModelScope.launch { engine.sync(SyncTrigger.MANUAL) }
    }
}
