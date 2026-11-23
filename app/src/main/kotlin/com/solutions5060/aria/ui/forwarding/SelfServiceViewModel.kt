package com.solutions5060.aria.ui.forwarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.pbx.ForwardType
import com.solutions5060.aria.pbx.SelfServiceSnapshot
import com.solutions5060.aria.pbx.SelfServiceState
import kotlinx.coroutines.flow.StateFlow

/** DND and call forwarding for Settings, the forwarding screen and the status chip. */
class SelfServiceViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<SelfServiceSnapshot> = SelfServiceState.state

    fun refresh() {
        if (!Fixtures.active) {
            SelfServiceState.refresh(getApplication())
        } else if (state.value.doNotDisturb == null) {
            // Fixture mode: DND off and no forwards, editable without a PBX.
            SelfServiceState.showFixture(SelfServiceSnapshot(doNotDisturb = false))
        }
    }

    fun setDoNotDisturb(on: Boolean) {
        if (Fixtures.active) {
            SelfServiceState.showFixture(state.value.copy(doNotDisturb = on))
            return
        }
        SelfServiceState.setDoNotDisturb(getApplication(), on)
    }

    fun saveForward(type: ForwardType, destination: String, ringTimeoutSecs: Int, enabled: Boolean, onDone: (String?) -> Unit) {
        if (Fixtures.active) {
            val s = state.value
            val kept = s.forwards.filterNot { it.type == type }
            val added = if (destination.isBlank()) emptyList() else listOf(
                com.solutions5060.aria.pbx.CallForward("fixture-${type.wire}", type, destination.trim(), ringTimeoutSecs, enabled),
            )
            SelfServiceState.showFixture(s.copy(forwards = kept + added))
            onDone(null)
            return
        }
        SelfServiceState.saveForward(getApplication(), type, destination, ringTimeoutSecs, enabled, onDone)
    }
}
