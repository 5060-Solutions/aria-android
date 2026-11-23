package com.solutions5060.aria.ui.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.service.GatewayConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * The status chip and connection sheet read [state]: signed out, offline,
 * connecting, registered or error, with the last error and the gateway.
 * [reconnect] is the sheet's "Reconnect" action.
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<ConnectionState> = GatewayConnection.state

    fun reconnect() = GatewayConnection.reconnect(getApplication())
}
