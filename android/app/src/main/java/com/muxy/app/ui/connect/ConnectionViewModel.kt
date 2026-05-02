package com.muxy.app.ui.connect

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muxy.app.data.DeviceCredentials
import com.muxy.app.data.DeviceCredentialsStore
import com.muxy.app.data.SavedDevice
import com.muxy.app.data.SavedDevicesStore
import com.muxy.app.data.SessionRepository
import com.muxy.app.model.AuthenticateDeviceParams
import com.muxy.app.model.PairDeviceParams
import com.muxy.app.model.TaggedValue
import com.muxy.app.model.MuxyMessage
import com.muxy.app.model.authenticateDeviceRequest
import com.muxy.app.model.decodePairingResult
import com.muxy.app.model.listProjectsRequest
import com.muxy.app.model.pairDeviceRequest
import com.muxy.app.net.MuxyClient
import com.muxy.app.net.TransportEvent
import com.muxy.app.net.newRequestId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class AwaitingApproval(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String, val clientID: String) : ConnectionState()
    data class Error(val message: String, val technicalDetails: String) : ConnectionState()
}

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    private val credentialsStore = DeviceCredentialsStore(app)
    private val devicesStore = SavedDevicesStore(app)
    private val client = MuxyClient()
    val session = SessionRepository(client, viewModelScope)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _savedDevices = MutableStateFlow(devicesStore.load())
    val savedDevices: StateFlow<List<SavedDevice>> = _savedDevices.asStateFlow()

    private val androidDeviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    private var lastDevice: SavedDevice? = null

    @Volatile private var isBackgrounded: Boolean = false
    @Volatile private var isReconnecting: Boolean = false
    private var transportJob: Job? = null

    init {
        // Observe socket-level events for the lifetime of the ViewModel. When the
        // socket closes or fails while the user is "Connected", kick off a silent
        // reconnect with backoff so the app self-heals instead of stranding the
        // user on a stale screen.
        transportJob = viewModelScope.launch {
            client.events.collect { evt ->
                when (evt) {
                    is TransportEvent.Closed, is TransportEvent.Failure -> {
                        if (_state.value is ConnectionState.Connected) {
                            lastDevice?.let { reconnectSilently(it) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun addDevice(name: String, host: String, port: Int) {
        val cleanName = name.ifBlank { "Mac" }
        val updated = (listOf(SavedDevice(cleanName, host, port)) +
                _savedDevices.value.filterNot { it.host == host && it.port == port })
        _savedDevices.value = updated
        devicesStore.save(updated)
    }

    fun removeDevice(device: SavedDevice) {
        val updated = _savedDevices.value.filterNot { it.id == device.id }
        _savedDevices.value = updated
        devicesStore.save(updated)
    }

    fun connect(device: SavedDevice) {
        lastDevice = device
        viewModelScope.launch { runConnection(device) }
    }

    fun reconnect() {
        lastDevice?.let { connect(it) }
    }

    fun disconnect() {
        client.disconnect()
        session.stopObserving()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun runConnection(device: SavedDevice) {
        _state.value = ConnectionState.Connecting(device.name)
        val credentials = credentialsStore.load()
        client.connect(device.host, device.port)
        delay(500)

        if (!authenticateOrPair(credentials, device.name)) return

        // Post-pairing: start observing events and refresh projects.
        session.startObserving()
        session.refreshProjects()
    }

    private suspend fun authenticateOrPair(credentials: DeviceCredentials, deviceLabel: String): Boolean {
        val authResp = try {
            client.send(
                authenticateDeviceRequest(
                    newRequestId(),
                    AuthenticateDeviceParams(
                        deviceID = credentials.deviceID.toString(),
                        deviceName = androidDeviceName,
                        token = credentials.token,
                    ),
                ),
                timeout = 10.seconds,
            )
        } catch (t: Throwable) {
            fail("Could not reach device", "authenticateDevice failed: ${t.message}")
            return false
        }

        val authError = authResp.error
        if (authError == null) return finishPairing(authResp.result, deviceLabel)
        if (authError.code != 401) {
            fail("Authentication failed", "code ${authError.code}: ${authError.message}")
            return false
        }

        _state.value = ConnectionState.AwaitingApproval(deviceLabel)
        val pairResp = try {
            client.send(
                pairDeviceRequest(
                    newRequestId(),
                    PairDeviceParams(
                        deviceID = credentials.deviceID.toString(),
                        deviceName = androidDeviceName,
                        token = credentials.token,
                    ),
                ),
                timeout = 120.seconds,
            )
        } catch (t: Throwable) {
            fail("Could not finish pairing", "pairDevice failed: ${t.message}")
            return false
        }

        val pairError = pairResp.error
        if (pairError != null) {
            val msg = if (pairError.code == 403) "Approval denied on Mac" else "Could not finish pairing"
            fail(msg, "code ${pairError.code}: ${pairError.message}")
            return false
        }
        return finishPairing(pairResp.result, deviceLabel)
    }

    private fun finishPairing(result: TaggedValue?, deviceLabel: String): Boolean {
        val pairing = decodePairingResult(result)
        if (pairing == null) {
            fail("Could not finish pairing", "expected 'pairing' result, got '${result?.type}'")
            return false
        }
        _state.value = ConnectionState.Connected(deviceLabel, pairing.clientID)
        session.setMyClientID(pairing.clientID)
        if (pairing.themeFg != null && pairing.themeBg != null) {
            session.applyInitialTheme(pairing.themeFg, pairing.themeBg, pairing.themePalette ?: emptyList())
        }
        return true
    }

    private fun fail(userMessage: String, technical: String) {
        if (isBackgrounded) return
        client.disconnect()
        session.stopObserving()
        _state.value = ConnectionState.Error(userMessage, technical)
    }

    fun onForeground() {
        isBackgrounded = false
        val device = lastDevice ?: return
        when (_state.value) {
            is ConnectionState.Error -> connect(device)
            is ConnectionState.Connected -> {
                if (!client.isConnected) reconnectSilently(device)
                else viewModelScope.launch { verifyOrReconnect(device) }
            }
            else -> Unit
        }
    }

    fun onBackground() {
        isBackgrounded = true
    }

    private suspend fun verifyOrReconnect(device: SavedDevice) {
        // Lightweight liveness check: re-list projects with a short timeout.
        // If it fails, the socket is dead even though OkHttp hasn't surfaced it yet.
        val ok = runCatching {
            withTimeoutOrNull(3.seconds) {
                client.send(listProjectsLikePing(), 3.seconds)
            } != null
        }.getOrDefault(false)
        if (!ok) reconnectSilently(device)
    }

    private fun listProjectsLikePing(): MuxyMessage.Request = listProjectsRequest(newRequestId())

    private fun reconnectSilently(device: SavedDevice) {
        if (isReconnecting) return
        isReconnecting = true
        viewModelScope.launch {
            try {
                // Drop stale ownership so the server's next paneOwnershipChanged
                // event drives whether we show the TakeOverOverlay.
                session.clearPaneOwners()
                val activeProject = session.activeProjectID.value
                val credentials = credentialsStore.load()

                // Retry with capped exponential backoff: 1s, 2s, 4s, 8s, 8s, ...
                var attempt = 0
                while (true) {
                    if (isBackgrounded) { delay(2.seconds); continue }
                    val ok = tryAuthenticateOnce(device, credentials)
                    if (ok) break
                    attempt++
                    val backoffMs = (1000L shl attempt.coerceAtMost(3)).coerceAtMost(8000L)
                    delay(backoffMs)
                }
                session.startObserving()
                session.refreshProjects()
                // Refresh the active project's workspace WITHOUT calling
                // selectProject — that would clear _workspace, which in turn
                // tears down the visible TerminalView's PaneSession and would
                // auto-takeover on recreate. We just want fresh state.
                if (activeProject != null) session.refreshWorkspace(activeProject)
            } finally {
                isReconnecting = false
            }
        }
    }

    /**
     * One reconnect attempt that never transitions to Error. Returns true on
     * successful re-authentication, false on any transport / auth failure so
     * the caller can back off and retry.
     */
    private suspend fun tryAuthenticateOnce(device: SavedDevice, credentials: DeviceCredentials): Boolean {
        return try {
            client.connect(device.host, device.port)
            delay(500)
            val resp = client.send(
                authenticateDeviceRequest(
                    newRequestId(),
                    AuthenticateDeviceParams(
                        deviceID = credentials.deviceID.toString(),
                        deviceName = androidDeviceName,
                        token = credentials.token,
                    ),
                ),
                timeout = 10.seconds,
            )
            if (resp.error != null) return false
            val pairing = decodePairingResult(resp.result) ?: return false
            session.setMyClientID(pairing.clientID)
            if (pairing.themeFg != null && pairing.themeBg != null) {
                session.applyInitialTheme(pairing.themeFg, pairing.themeBg, pairing.themePalette ?: emptyList())
            }
            // Refresh the visible state's clientID without touching the screen.
            (_state.value as? ConnectionState.Connected)?.let {
                _state.value = ConnectionState.Connected(it.deviceName, pairing.clientID)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onCleared() {
        client.disconnect()
        session.stopObserving()
        super.onCleared()
    }
}
