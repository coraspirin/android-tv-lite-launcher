package local.kutu.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import local.kutu.mirror.MirrorActivity
import local.kutu.mirror.Prefs
import local.kutu.mirror.R
import local.kutu.mirror.bridge.LogListener
import local.kutu.mirror.bridge.NativeBridge
import local.kutu.mirror.bridge.RaopCallbackHandler
import local.kutu.mirror.discovery.NsdServiceManager
import local.kutu.mirror.realDisplaySize
import local.kutu.mirror.renderer.AirPlayVideoPlayer
import local.kutu.mirror.renderer.AudioRenderer
import local.kutu.mirror.renderer.VideoRenderer
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * AirPlay receiver service. Handles the two things an iPhone can send:
 *
 *  - **screen mirroring** (Control Centre -> Screen Mirroring): an H.264 stream decoded
 *    by VideoRenderer
 *  - **AirPlay Video** (the AirPlay button inside a video): the sender hands over a URL
 *    and this box plays it itself, through AirPlayVideoPlayer
 *
 * Differences from upstream that the master guide requires:
 *  - no DACP, no media session, no artwork, no downloader
 *  - the partial wakelock is held only for a live session (guide 9)
 *  - MirrorActivity is opened from onMirrorRunning(true), a genuine /play, or a minted
 *    PIN, and nowhere else. A bare connection, a port scan, /info or a /playback-info
 *    poll never opens it (guide 10)
 *  - every session teardown releases the video pipeline, so a later session can never
 *    repaint the previous sender's last frame (guide 10)
 */
class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    val videoRenderer = VideoRenderer(this)
    val audioRenderer = AudioRenderer()
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val _mainHandler = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    /**
     * True between onMirrorRunning(true) and onMirrorRunning(false). This is the only
     * "genuine mirroring" signal; a bare TCP connect, a port scan, /info or a
     * negotiation that never reaches mirroring never sets it.
     */
    private val _mirrorSession = MutableStateFlow(false)
    val mirrorSession = _mirrorSession.asStateFlow()

    /** True while AirPlay Video is playing a URL the sender handed over. */
    private val _videoPlayback = MutableStateFlow(false)
    val videoPlayback = _videoPlayback.asStateFlow()

    /** either kind of session is live, so MirrorActivity has something legitimate to show */
    fun sessionActive(): Boolean = _mirrorSession.value || _videoPlayback.value

    /** invoked on the main thread when a session starts (true) or ends (false) */
    var mirrorSessionCallback: ((Boolean) -> Unit)? = null

    // the surface MirrorActivity handed over; routed to whichever consumer is live
    @Volatile private var _surface: Surface? = null

    @Volatile private var _videoPlaying = false

    /** invoked on the main thread when the mirrored picture's aspect ratio is known */
    var videoAspectCallback: ((Float) -> Unit)? = null

    var logCallback: ((String) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    // senders park volume at -30 as the route closes; a session must not leave the box silenced
    private var _preZeroIdx = -1

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        airPlayVideoPlayer.onVideoSize = { _, _, aspect ->
            _videoAspect.value = aspect
            _mainHandler.post { videoAspectCallback?.invoke(aspect) }
        }
        airPlayVideoPlayer.onEnded = { _endVideoPlayback("AirPlay Video ended") }
        airPlayVideoPlayer.onPlaybackInfo = { snap ->
            // the sender holds its /play until readyToPlay, and polls /playback-info for
            // position; duration = -1 is the "finished" sentinel
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(
                    nativeHandle, snap.position, snap.duration, snap.rate, snap.ready)
            }
        }

        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SERVER) {
            promoteToForeground()
            val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
            startServer(name, ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.DEF_SERVER_NAME }

        // guide 9.3: the multicast lock is the only lock an idle receiver holds.
        // The CPU wakelock is taken in onMirrorRunning(true), not here.
        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val (reqW, reqH) = _displaySize(clamp = false)
        val h265 = videoRenderer.selectDecoders(
            reqW, reqH, maxFps, prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED))

        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        videoRenderer.selector.maxOperatingRate = when (prefs.getString(Prefs.OPERATING_RATE, Prefs.DEF_OPERATING_RATE)) {
            Prefs.ON -> true; Prefs.OFF -> false; else -> null
        }
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease =
            prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)

        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        // guide 8: ALAC is gone with FFmpeg, so it is never advertised. AAC-LC/AAC-ELD
        // is what screen mirroring actually uses.
        NativeBridge.nativeSetCodecs(nativeHandle, false, true)
        // AirPlay Video: the sender hands over a URL and this box plays it. Without this
        // the AirPlay button inside a video offers "Kutu" but nothing happens, because
        // dns-sd feature bits 0 and 4 would be clear.
        NativeBridge.nativeSetHlsEnabled(nativeHandle, true)
        NativeBridge.nativeSetLang(nativeHandle, "", "", resources.configuration.locales.toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetAudioEnabled(nativeHandle, true)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        nsdManager?.registerRaop(raopName, port, raopTxt)
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port as \"$resolvedName\"")
    }

    private fun _orientationFollowsDevice(): Boolean =
        prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) == Prefs.AUTO

    private fun _displaySize(clamp: Boolean = true): Pair<Int, Int> {
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val portrait = when (res) {
            "portrait" -> true
            "landscape" -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == "portrait" || res == "landscape") return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        _releaseWakeLock()
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    /** tears the receiver down without stopping the service itself */
    private fun _teardown() {
        _endVideoPlayback("AirPlay Video stopped (receiver teardown)")
        _endMirrorSession(notify = true)
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        _releaseWakeLock()
        videoRenderer.release()
        _videoResolution.value = ""
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        clearPin()
    }

    fun stopServer() {
        _teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    /**
     * Ends the current session and immediately re-advertises. UxPlay exposes no way to
     * drop a single client, so the receiver is cycled: the sender sees a disconnect and
     * "Kutu" is back in the AirPlay list straight away. Used by the BACK key.
     */
    fun endSessionAndRestart() {
        if (_serverState.value != ServerState.RUNNING) return
        log("Session ended locally, restarting receiver")
        val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
        _teardown()
        startServer(name, ensureServiceStarted = false)
    }

    fun setVideoSurface(surface: Surface) {
        _surface = surface
        _routeSurface()
    }

    fun clearVideoSurface(surface: Surface) {
        if (_surface === surface) _surface = null
        airPlayVideoPlayer.clearSurface(surface)
        videoRenderer.clearSurface(surface)
    }

    /**
     * MirrorActivity owns one SurfaceView for both kinds of session. Hand it to whichever
     * consumer is live, and take it away from the other so two producers never share it.
     */
    private fun _routeSurface() {
        val s = _surface ?: return
        if (_videoPlayback.value) {
            videoRenderer.clearSurface(s)
            airPlayVideoPlayer.setSurface(s)
        } else {
            airPlayVideoPlayer.clearSurface(s)
            videoRenderer.setSurface(s)
        }
    }

    /** local stop, e.g. BACK on the remote. The sender self-syncs from its next poll. */
    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)")

    fun togglePlayPause() {
        if (!_videoPlayback.value) return
        _videoPlaying = !_videoPlaying
        airPlayVideoPlayer.setPlaying(_videoPlaying)
    }

    fun seekVideoBy(deltaMs: Long) {
        if (!_videoPlayback.value) return
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            val aspect = w / h
            _videoAspect.value = aspect
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mainHandler.post { videoAspectCallback?.invoke(aspect) }
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        val frac = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        _mainHandler.post {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val idx = (frac * max).roundToInt()
            _preZeroIdx = if (idx == 0) (if (_preZeroIdx < 0) cur else _preZeroIdx) else -1
            if (idx != cur) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (vol == 0) -144f else -30f + 30f * vol / max
    }

    override fun onAudioTeardown() {
        // nothing to do: playout is released when the last client goes away
    }

    override fun onConnectionInit() {
        _connectionCount.value++
        // Deliberately does NOT open any UI. A TCP connect, a port scan, /info or a
        // negotiation that never reaches mirroring must never put anything on screen.
        log("Client connected (${_connectionCount.value})")
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            // clients may drop without sending /stop
            _endVideoPlayback("AirPlay Video stopped (disconnect)")
            _endMirrorSession(notify = true)
            // last client gone: release the audio output device to save power
            audioRenderer.stop()
            _mainHandler.post {
                if (_preZeroIdx >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _preZeroIdx, 0)
                    _preZeroIdx = -1
                }
            }
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
        if (reason != 0) {
            _endVideoPlayback("AirPlay Video stopped (connection reset)")
            _endMirrorSession(notify = true)
        }
    }

    override fun onDisplayPin(pin: String) {
        if (_lastPin == pin) return
        _lastPin = pin
        _mainHandler.post {
            pinCallback?.invoke(pin)
            // a minted PIN is a genuine pairing prompt, the one non-mirroring case the
            // guide allows the activity to be on screen for
            launchMirrorActivity()
        }
        _updateNotification()
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) _startMirrorSession() else _endMirrorSession(notify = true)
    }

    // AirPlay Video: the sender hands over a URL for this box to play.

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        _videoPlaying = true
        _videoPlayback.value = true
        _acquireWakeLock()
        airPlayVideoPlayer.play(location, startPositionSeconds)
        _mainHandler.post {
            _routeSurface()
            mirrorSessionCallback?.invoke(true)
            if (shouldLaunchOnConnect()) launchMirrorActivity()
        }
        log("AirPlay Video play @ ${startPositionSeconds}s")
    }

    override fun onVideoScrub(positionSeconds: Float) {
        airPlayVideoPlayer.scrub(positionSeconds)
    }

    override fun onVideoRate(rate: Float) {
        _videoPlaying = rate > 0f
        airPlayVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() = _endVideoPlayback("AirPlay Video stopped")

    /**
     * The sender's `/playback-info` poll. It starts about a second *before* `/play`, and
     * guide 10 names it explicitly as something that must not put anything on screen, so
     * this is deliberately empty.
     */
    override fun onVideoSessionPoll() {}

    private fun _endVideoPlayback(message: String) {
        if (!_videoPlayback.value) return
        _videoPlayback.value = false
        _videoPlaying = false
        airPlayVideoPlayer.stop()
        _releaseWakeLock()
        _mainHandler.post { mirrorSessionCallback?.invoke(false) }
        log(message)
    }

    // guide 8: no music/artwork/DACP surface
    override fun onMetadata(data: ByteArray) {}
    override fun onCoverArt(data: ByteArray) {}
    override fun onProgress(start: Long, curr: Long, end: Long) {}
    override fun onDacpId(dacpId: String, activeRemote: String) {}

    // mirroring session

    private fun _startMirrorSession() {
        if (_mirrorSession.value) return
        // a sender that switches from AirPlay Video to mirroring must not leave the
        // player holding the surface
        if (_videoPlayback.value) _endVideoPlayback("AirPlay Video superseded by mirroring")
        _mirrorSession.value = true
        videoRenderer.startSession()
        _acquireWakeLock()
        _mainHandler.post {
            _routeSurface()
            mirrorSessionCallback?.invoke(true)
            if (shouldLaunchOnConnect()) launchMirrorActivity()
        }
        log("Mirroring started")
    }

    private fun _endMirrorSession(notify: Boolean) {
        if (!_mirrorSession.value) return
        _mirrorSession.value = false
        // guide 10: complete cleanup on every termination path. release() stops and
        // releases the decoder and destroys the GL pipeline, so no frame state survives
        // into the next session.
        videoRenderer.stopSession()
        videoRenderer.release()
        _releaseWakeLock()
        if (notify) _mainHandler.post { mirrorSessionCallback?.invoke(false) }
        log("Mirroring stopped")
    }

    private fun _acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kutu:mirror").apply { acquire() }
    }

    /** only lets go once neither kind of session is live */
    private fun _releaseWakeLock() {
        if (sessionActive()) return
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun clearPin() {
        if (_lastPin == null) return
        _lastPin = null
        _mainHandler.post { pinCallback?.invoke(null) }
        _updateNotification()
    }

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    /**
     * No content intent: MirrorActivity is not exported and must not be reachable by
     * tapping a notification, only by a genuine mirroring session.
     */
    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
        val pin = _lastPin
        if (pin != null) {
            builder.setContentTitle(getString(R.string.notification_pin_title))
                .setContentText(getString(R.string.notification_pin_text, pin))
        } else {
            builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
        }
        return builder.build()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), 0)
        foregroundStarted = true
    }

    private fun _updateNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    /** a PIN prompt is genuinely on screen; the only other reason MirrorActivity may live */
    fun hasActivePin(): Boolean = _lastPin != null

    private fun shouldLaunchOnConnect(): Boolean =
        prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)

    private fun launchMirrorActivity() {
        val launchIntent = Intent(this, MirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open MirrorActivity", e)
        }
    }

    enum class ServerState {
        STOPPED, RUNNING, ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "kutu_mirror"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_SERVER = "local.kutu.mirror.START_SERVER"
        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
    }
}
