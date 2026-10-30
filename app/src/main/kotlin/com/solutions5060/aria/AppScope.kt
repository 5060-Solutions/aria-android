package com.solutions5060.aria

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work that must outlive the component that started it, such as a service
 * that Android stops as soon as its callback returns. Lives as long as the
 * process; one failed job does not cancel the others.
 */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
