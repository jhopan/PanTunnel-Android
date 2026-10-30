package id.jhopanstore.pantunnel.service

import id.jhopanstore.pantunnel.core.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process event bus between the VPN service and the UI.
 *
 * The service is the only publisher. Activities observe [state] for instant updates and fall
 * back to [TunnelStatusStore] when they start while the service is already running.
 */
object TunnelBus {

    data class Event(
        val state: TunnelState = TunnelState.Idle,
        val detail: String = "",
        val profileName: String = "",
        val logLines: List<String> = emptyList(),
        val revision: Long = 0L,
    )

    private val _state = MutableStateFlow(Event())
    val state: StateFlow<Event> = _state.asStateFlow()

    @Synchronized
    fun publish(event: Event) {
        _state.value = event.copy(revision = _state.value.revision + 1)
    }

    fun reset() {
        publish(Event())
    }
}
