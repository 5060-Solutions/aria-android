package com.solutions5060.aria.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The default network as the app last saw it, for the connection sheet and diagnostics. */
data class NetworkSnapshot(
    val online: Boolean = true,
    val transports: Set<NetTransport> = emptySet(),
    val validated: Boolean? = null,
)

object NetworkStatus {
    private val _state = MutableStateFlow(NetworkSnapshot())
    val state: StateFlow<NetworkSnapshot> = _state.asStateFlow()

    fun update(snapshot: NetworkSnapshot) {
        _state.value = snapshot
    }
}
