package ca.sixis.twitchtts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object ChatRepository {
    private val _messages = MutableSharedFlow<ChatMessage>(replay = 100, extraBufferCapacity = 100)
    val messages = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    suspend fun addMessage(message: ChatMessage) {
        _messages.emit(message)
    }

    fun updateState(state: ConnectionState) {
        _connectionState.value = state
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class ChatMessage(val user: String, val message: String)
