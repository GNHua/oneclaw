package com.oneclaw.shadow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.security.GoogleAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Google Account settings screen.
 */
class GoogleAuthViewModel(
    private val googleAuthManager: GoogleAuthManager
) : ViewModel() {

    data class UiState(
        val clientId: String = "",
        val clientSecret: String = "",
        val isSignedIn: Boolean = false,
        val accountEmail: String? = null,
        val hasCredentials: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        _uiState.update {
            it.copy(
                clientId = googleAuthManager.getClientId() ?: "",
                clientSecret = googleAuthManager.getClientSecret() ?: "",
                isSignedIn = googleAuthManager.isSignedIn(),
                accountEmail = googleAuthManager.getAccountEmail(),
                hasCredentials = googleAuthManager.hasOAuthCredentials()
            )
        }
    }

    fun onClientIdChanged(value: String) {
        _uiState.update { it.copy(clientId = value) }
    }

    fun onClientSecretChanged(value: String) {
        _uiState.update { it.copy(clientSecret = value) }
    }

    fun saveCredentials() {
        googleAuthManager.saveOAuthCredentials(
            _uiState.value.clientId,
            _uiState.value.clientSecret
        )
        _uiState.update { it.copy(hasCredentials = true, error = null) }
    }

    fun signIn() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = googleAuthManager.authorize()) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSignedIn = true,
                            accountEmail = result.data
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.exception?.message ?: result.message
                        )
                    }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            googleAuthManager.signOut()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isSignedIn = false,
                    accountEmail = null
                )
            }
        }
    }
}
