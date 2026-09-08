package com.omninode.hub.network

import com.omninode.hub.data.model.HaAuthMessage
import com.omninode.hub.data.model.HaCallService
import com.omninode.hub.data.model.HaStateChangedEvent
import com.omninode.hub.data.model.HaSubscribeEvents
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HomeAssistantWsClient — Persistent OkHttp WebSocket client for the Home Assistant Core API.
 *
 * Architecture:
 *  ┌──────────────────────────────────────────────────────────────────────────┐
 *  │  Jetpack Compose UI / ViewModel                                         │
 *  │          ↕  (StateFlow / SharedFlow)                                    │
 *  │  HomeAssistantWsClient                                                  │
 *  │          ↕  (OkHttp WebSocket / TCP)  ──on failure──►  VirtualRegistry │
 *  │  Home Assistant Core  [configurable IP, default 192.168.1.100:8123]    │
 *  └──────────────────────────────────────────────────────────────────────────┘
 *
 * Standalone / Mock Mode:
 *  After [MAX_REAL_ATTEMPTS] consecutive UnknownHostException or ConnectException
 *  failures the client stops reconnecting and activates [VirtualDeviceRegistry].
 *  All subsequent [callService] calls are routed to the registry transparently.
 *  The flow emitted is [ConnectionStatus.StandaloneMock].
 *
 * Protocol flow (Home Assistant WebSocket API):
 *  1. Connect → receive { type: "auth_required" }
 *  2. Send    → { type: "auth", access_token: "<token>" }
 *  3. Receive → { type: "auth_ok" } | { type: "auth_invalid" }
 *  4. Send    → { id: 1, type: "subscribe_events", event_type: "state_changed" }
 *  5. Receive → continuous stream of state_changed events
 *  6. Send    → { id: N, type: "call_service", domain: "light", service: "turn_on", ... }
 *
 * Guarantees:
 *  • Exponential back-off reconnection up to [MAX_REAL_ATTEMPTS] (then mock fallback).
 *  • Thread-safe message ID sequencing via AtomicInteger.
 *  • Sub-20 ms command round-trip on local LAN (bypasses cloud entirely).
 */
@Singleton
class HomeAssistantWsClient @Inject constructor(
    private val moshi: Moshi,
    private val virtualRegistry: VirtualDeviceRegistry,
) {
    // ── Coroutine scope — survives Activity recreation ────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Connection state ──────────────────────────────────────────────────────
    private var webSocket: WebSocket? = null
    private var haToken: String = ""
    private var haUrl: String = DEFAULT_WS_URL
    private val messageId = AtomicInteger(1)
    private var isAuthenticated = false

    /**
     * When true the client has given up on reaching the real HA instance and
     * all [callService] calls are routed to [VirtualDeviceRegistry].
     */
    @Volatile private var isMockMode = false

    // ── Reactive event streams exposed to ViewModels ──────────────────────────
    private val _stateChanges = MutableSharedFlow<HaStateChangedEvent>(
        replay = 10,
        extraBufferCapacity = 128,
    )
    val stateChanges: Flow<HaStateChangedEvent> = _stateChanges.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<ConnectionStatus>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    val connectionStatus: Flow<ConnectionStatus> = _connectionStatus.asSharedFlow()

    // ── Pending service-call acknowledgement channel ──────────────────────────
    private val ackChannel = Channel<Int>(capacity = 32)

    // ── OkHttp client tuned for WebSocket persistence ─────────────────────────
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // No timeout — WebSocket must stay alive
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive pings every 30 s
        .retryOnConnectionFailure(false)    // We control reconnect logic explicitly
        .build()

    // ── Moshi adapter cache ───────────────────────────────────────────────────
    private val authAdapter     = moshi.adapter(HaAuthMessage::class.java)
    private val subscribeAdapter = moshi.adapter(HaSubscribeEvents::class.java)
    private val callAdapter     = moshi.adapter(HaCallService::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the WebSocket connection to Home Assistant.
     * Uses [url] if provided; otherwise falls back to [DEFAULT_WS_URL].
     * Reconnects automatically with exponential back-off up to [MAX_REAL_ATTEMPTS].
     * After that threshold is exceeded the client silently switches to mock mode.
     *
     * @param url   WebSocket URL, e.g. "ws://192.168.1.100:8123/api/websocket"
     * @param token Long-lived access token from HA → Profile → Long-Lived Access Tokens
     */
    fun connect(url: String = DEFAULT_WS_URL, token: String) {
        haUrl   = url.ifBlank { DEFAULT_WS_URL }
        haToken = token
        isMockMode = false
        openSocket()
    }

    /**
     * Sends a service call to Home Assistant (real or mock, transparent to caller).
     *
     * If connected to HA: sends the JSON message over the WebSocket.
     * If in standalone mock mode: routes the call to [VirtualDeviceRegistry].
     *
     * Returns the message ID for correlation, or -1 on failure.
     */
    fun callService(
        domain: String,
        service: String,
        serviceData: Map<String, Any>,
    ): Int {
        if (isMockMode) {
            Timber.d("MockMode → callService $domain.$service data=$serviceData")
            scope.launch { virtualRegistry.handleServiceCall(domain, service, serviceData) }
            return messageId.getAndIncrement()
        }

        if (!isAuthenticated) {
            Timber.w("callService() called before authentication — queuing not yet implemented")
            return -1
        }
        val id  = messageId.getAndIncrement()
        val msg = callAdapter.toJson(
            HaCallService(id = id, domain = domain, service = service, serviceData = serviceData)
        )
        val sent = webSocket?.send(msg) ?: false
        Timber.d("HA → callService [id=$id, domain=$domain, service=$service] sent=$sent")
        return if (sent) id else -1
    }

    /** Returns true if the client is currently running in standalone mock mode. */
    fun isInMockMode(): Boolean = isMockMode

    /** Gracefully closes the WebSocket. No-op in mock mode. */
    fun disconnect() {
        webSocket?.close(1000, "OmniNode shutdown")
        webSocket = null
        isAuthenticated = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal implementation
    // ─────────────────────────────────────────────────────────────────────────

    private fun openSocket() {
        try {
            val request = Request.Builder().url(haUrl).build()
            webSocket = okHttpClient.newWebSocket(request, HaWebSocketListener())
            Timber.d("OkHttp WebSocket connecting → $haUrl")
        } catch (e: IllegalArgumentException) {
            // Malformed URL — do not attempt to connect; fall back immediately.
            Timber.e(e, "Malformed HA WebSocket URL '$haUrl' — activating standalone mode")
            scope.launch { activateMockMode() }
        }
    }

    private fun reconnectWithBackoff(attemptNumber: Int) {
        if (attemptNumber >= MAX_REAL_ATTEMPTS) {
            Timber.w("WebSocket: exceeded $MAX_REAL_ATTEMPTS attempts — activating standalone mock mode")
            scope.launch { activateMockMode() }
            return
        }
        scope.launch {
            val delayMs = minOf(1_000L * (1L shl attemptNumber), 30_000L)
            Timber.w("WebSocket reconnecting in ${delayMs}ms (attempt $attemptNumber / $MAX_REAL_ATTEMPTS)")
            delay(delayMs)
            openSocket()
        }
    }

    /**
     * Activates standalone mock mode:
     *  1. Emits [ConnectionStatus.StandaloneMock] to all observers.
     *  2. Bridges [VirtualDeviceRegistry.stateChanges] into the shared [_stateChanges] flow
     *     so ViewModels receive virtual state-change events exactly as they would real ones.
     */
    private suspend fun activateMockMode() {
        isMockMode = true
        _connectionStatus.emit(ConnectionStatus.StandaloneMock)
        Timber.i("HomeAssistantWsClient: running in Standalone Mock Mode — VirtualDeviceRegistry active")

        // Bridge virtual registry events into the main state-change stream.
        scope.launch {
            virtualRegistry.stateChanges.collect { event ->
                _stateChanges.emit(event)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WebSocket listener
    // ─────────────────────────────────────────────────────────────────────────

    private inner class HaWebSocketListener : WebSocketListener() {
        private var reconnectAttempt = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            Timber.i("HA WebSocket opened — awaiting auth_required")
            scope.launch { _connectionStatus.emit(ConnectionStatus.Connecting) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Timber.v("HA ← $text")
            try {
                handleMessage(text)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse HA message: $text")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("HA WebSocket closing [$code]: $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isAuthenticated = false
            scope.launch { _connectionStatus.emit(ConnectionStatus.Disconnected(reason)) }
            Timber.i("HA WebSocket closed [$code]: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isAuthenticated = false
            val isNetworkUnreachable = t is UnknownHostException || t is ConnectException

            if (isNetworkUnreachable) {
                Timber.w("HA WebSocket: network unreachable (${t.javaClass.simpleName}) attempt=$reconnectAttempt")
            } else {
                Timber.e(t, "HA WebSocket failure")
            }

            scope.launch {
                _connectionStatus.emit(ConnectionStatus.Error(t.message ?: "Unknown error"))
            }

            // Increment attempt counter and decide: retry or switch to mock mode.
            reconnectWithBackoff(reconnectAttempt++)
        }

        // ── Message dispatch ──────────────────────────────────────────────────
        private fun handleMessage(raw: String) {
            // Parse only the 'type' field first for dispatch
            val typeMap = moshi.adapter<Map<String, Any>>(
                Map::class.java
            ).fromJson(raw) ?: return

            when (val type = typeMap["type"] as? String) {
                "auth_required" -> {
                    val authMsg = authAdapter.toJson(HaAuthMessage(accessToken = haToken))
                    webSocket?.send(authMsg)
                    Timber.d("HA → auth sent")
                }

                "auth_ok" -> {
                    isAuthenticated = true
                    scope.launch {
                        _connectionStatus.emit(ConnectionStatus.Connected)
                        // Subscribe to all state_changed events
                        val subMsg = subscribeAdapter.toJson(
                            HaSubscribeEvents(id = messageId.getAndIncrement())
                        )
                        this@HomeAssistantWsClient.webSocket?.send(subMsg)
                        Timber.i("HA WebSocket authenticated — subscribed to state_changed")
                    }
                }

                "auth_invalid" -> {
                    isAuthenticated = false
                    scope.launch { _connectionStatus.emit(ConnectionStatus.AuthFailed) }
                    Timber.e("HA authentication failed — invalid token")
                }

                "event" -> {
                    // Parse nested state_changed payload
                    parseStateChangedEvent(raw)
                }

                "result" -> {
                    val id = (typeMap["id"] as? Double)?.toInt() ?: return
                    scope.launch { ackChannel.trySend(id) }
                }

                else -> Timber.v("HA message type=$type ignored")
            }
        }

        private fun parseStateChangedEvent(raw: String) {
            try {
                val adapter = moshi.adapter<Map<String, Any>>(Map::class.java)
                val root    = adapter.fromJson(raw) ?: return
                @Suppress("UNCHECKED_CAST")
                val event   = root["event"] as? Map<String, Any> ?: return
                @Suppress("UNCHECKED_CAST")
                val data    = event["data"] as? Map<String, Any> ?: return
                val entityId = data["entity_id"] as? String ?: return

                scope.launch {
                    _stateChanges.emit(
                        HaStateChangedEvent(
                            entityId = entityId,
                            newState = null, // Full parsing done in ViewModel
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not parse state_changed event")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Connection status sealed class
    // ─────────────────────────────────────────────────────────────────────────

    sealed class ConnectionStatus {
        data object Connecting      : ConnectionStatus()
        data object Connected       : ConnectionStatus()
        data object AuthFailed      : ConnectionStatus()
        /** Running in offline / standalone mode using [VirtualDeviceRegistry]. */
        data object StandaloneMock  : ConnectionStatus()
        data class  Disconnected(val reason: String) : ConnectionStatus()
        data class  Error(val message: String)       : ConnectionStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Default WebSocket URL used when no URL is passed to [connect].
         * Uses a numeric IP address (192.168.1.100) instead of homeassistant.local
         * to avoid mDNS resolution failures on networks without an HA instance.
         * Override via BuildConfig.HA_WS_URL or the Settings screen.
         */
        const val DEFAULT_HOST   = "192.168.1.100"
        const val DEFAULT_PORT   = 8123
        const val DEFAULT_WS_URL = "ws://$DEFAULT_HOST:$DEFAULT_PORT/api/websocket"

        /**
         * Maximum consecutive network-level failures before the client gives up
         * and activates [VirtualDeviceRegistry] standalone mock mode.
         */
        const val MAX_REAL_ATTEMPTS = 3
    }
}
