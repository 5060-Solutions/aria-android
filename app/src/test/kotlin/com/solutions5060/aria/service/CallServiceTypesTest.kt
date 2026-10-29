package com.solutions5060.aria.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CallServiceTypesTest {

    @Test
    fun microphoneIsTriedFirstThenPhoneCallAlone() {
        val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(listOf<Int?>(phone or mic, phone), IncomingCallService.foregroundTypeAttempts(34))
        assertEquals(listOf<Int?>(phone or mic, phone), IncomingCallService.foregroundTypeAttempts(29))
    }

    @Test
    fun beforeTypedServicesTheUntypedCallIsUsed() {
        assertEquals(listOf<Int?>(null), IncomingCallService.foregroundTypeAttempts(28))
    }
}
