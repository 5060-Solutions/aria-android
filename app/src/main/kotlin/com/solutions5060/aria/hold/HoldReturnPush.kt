package com.solutions5060.aria.hold

/**
 * The Hold for me fields of an incoming-call push: the wait id the PBX is
 * ringing for (`X-Aria-Hold-Return`) and who is back (`X-Aria-Hold-Return-Name`).
 * Each is null when the gateway omitted it.
 */
data class HoldReturnPush(val id: String?, val name: String?) {
    companion object {
        /** Reads the FCM data map; the gateway sends camelCase, snake_case is accepted too. */
        fun from(data: Map<String, String>): HoldReturnPush = HoldReturnPush(
            id = (data["holdReturnId"] ?: data["hold_return_id"])?.trim()?.ifEmpty { null },
            name = (data["holdReturnName"] ?: data["hold_return_name"])?.trim()?.ifEmpty { null },
        )
    }
}
