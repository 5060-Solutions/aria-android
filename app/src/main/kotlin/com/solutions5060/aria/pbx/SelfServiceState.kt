package com.solutions5060.aria.pbx

import android.content.Context
import android.util.Log
import com.solutions5060.aria.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the status chip and Settings show about the user's PBX features. */
data class SelfServiceSnapshot(
    /** Null until first loaded. */
    val doNotDisturb: Boolean? = null,
    val forwards: List<CallForward> = emptyList(),
    val loading: Boolean = false,
    /** The last failure, for the screen that caused it. */
    val error: String? = null,
) {
    /** An enabled forward exists: the status chip shows the forwarding arrow. */
    val forwardingOn: Boolean get() = forwards.any { it.enabled }

    fun forward(type: ForwardType): CallForward? = forwards.firstOrNull { it.type == type }
}

/**
 * Process-wide DND and forwarding state, so the status chip, Settings and the
 * forwarding screen agree. Refreshed when the app comes to the foreground and
 * after each change; [repository] is injectable for tests.
 */
object SelfServiceState {

    private const val TAG = "SelfServiceState"

    private val _state = MutableStateFlow(SelfServiceSnapshot())
    val state: StateFlow<SelfServiceSnapshot> = _state.asStateFlow()

    @Volatile
    internal var repository: ((Context) -> SelfServiceRepository) = { SelfServiceRepository.from(it) }

    fun refresh(context: Context) {
        val app = context.applicationContext
        _state.update { it.copy(loading = true) }
        AppScope.scope.launch {
            try {
                val repo = repository(app)
                val features = repo.features()
                val forwards = repo.forwards()
                _state.update { it.copy(doNotDisturb = features.doNotDisturb, forwards = forwards, loading = false, error = null) }
                // Number formatting only: a seat that may not read the settings (403) keeps the device's region.
                try {
                    DialCountry.setFromPbx(app, repo.callingCountry())
                } catch (e: Exception) {
                    Log.w(TAG, "Calling country unavailable: ${e.javaClass.simpleName}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** Sets DND, showing it at once and reverting if the PBX refuses. */
    fun setDoNotDisturb(context: Context, on: Boolean) {
        val app = context.applicationContext
        val before = _state.value.doNotDisturb
        _state.update { it.copy(doNotDisturb = on, error = null) }
        AppScope.scope.launch {
            try {
                repository(app).setDoNotDisturb(on)
            } catch (e: Exception) {
                Log.w(TAG, "DND change failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(doNotDisturb = before, error = e.message) }
            }
        }
    }

    /**
     * Saves the forward of [type] (an empty destination removes it). Calls
     * [onDone] on the main thread with null, or the PBX's reason.
     */
    fun saveForward(
        context: Context,
        type: ForwardType,
        destination: String,
        ringTimeoutSecs: Int,
        enabled: Boolean,
        onDone: (String?) -> Unit,
    ) {
        val app = context.applicationContext
        val existing = _state.value.forward(type)
        AppScope.scope.launch {
            val error = try {
                val saved = repository(app).saveForward(type, existing, destination, ringTimeoutSecs, enabled)
                _state.update { s ->
                    s.copy(forwards = s.forwards.filterNot { it.type == type } + listOfNotNull(saved), error = null)
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "Forward save failed: ${e.javaClass.simpleName}")
                e.message ?: e.javaClass.simpleName
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(error) }
        }
    }

    fun clear() {
        _state.value = SelfServiceSnapshot()
    }

    /** Debug fixture mode: show [snapshot] without a PBX. */
    fun showFixture(snapshot: SelfServiceSnapshot) {
        _state.value = snapshot
    }
}
