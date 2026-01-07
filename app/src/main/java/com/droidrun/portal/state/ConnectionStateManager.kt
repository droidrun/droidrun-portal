package com.droidrun.portal.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

object ConnectionStateManager {
    private val _connectionState = MutableLiveData(ConnectionState.DISCONNECTED)
    val connectionState: LiveData<ConnectionState> = _connectionState

    fun setState(state: ConnectionState) {
        // Use postValue to allow calling from background threads
        _connectionState.postValue(state)
    }
    
    fun getState(): ConnectionState {
        return _connectionState.value ?: ConnectionState.DISCONNECTED
    }
}

