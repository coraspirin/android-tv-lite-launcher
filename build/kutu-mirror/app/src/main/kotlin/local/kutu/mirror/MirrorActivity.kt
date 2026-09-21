package local.kutu.mirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import local.kutu.mirror.service.AirPlayService

/**
 * The picture, for both kinds of session: screen mirroring and AirPlay Video. Not
 * exported, not in any launcher, and opened only by AirPlayService when a session
 * genuinely starts or a PIN prompt is minted (master guide section 10).
 *
 * It finishes itself the moment the session ends, and detaches its Surface before
 * finishing so the next session cannot inherit a frame from this one.
 */
class MirrorActivity : Activity() {

    private var service: AirPlayService? = null
    private var bound = false
    private var boundSurface: android.view.Surface? = null
    private var finishing = false

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var pinView: TextView

    private var aspect = 16f / 9f

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            boundSurface = holder.surface
            service?.setVideoSurface(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
            boundSurface = holder.surface
            service?.setVideoSurface(holder.surface)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            service?.clearVideoSurface(holder.surface)
            if (boundSurface === holder.surface) boundSurface = null
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.mirrorSessionCallback = { running -> if (!running) endAndFinish(disconnect = false) }
            svc.videoAspectCallback = { a -> applyAspect(a) }
            svc.pinCallback = { pin -> showPin(pin) }
            // guide 10: if no session and no PIN prompt is live, there is nothing
            // legitimate to show - leave immediately.
            if (!svc.sessionActive() && !svc.hasActivePin()) {
                endAndFinish(disconnect = false)
                return
            }
            applyAspect(svc.videoAspect.value)
            boundSurface?.let { svc.setVideoSurface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            endAndFinish(disconnect = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(holderCallback)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER)
        }
        pinView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER)
        }
        root.addView(surfaceView)
        root.addView(pinView)
        setContentView(root)

        bound = bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) finish()
    }

    /** letterbox the mirrored picture instead of stretching it */
    private fun applyAspect(a: Float) {
        if (a <= 0f) return
        aspect = a
        root.post {
            val availW = root.width
            val availH = root.height
            if (availW == 0 || availH == 0) return@post
            var w = availW
            var h = (availW / aspect).toInt()
            if (h > availH) {
                h = availH
                w = (availH * aspect).toInt()
            }
            surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            surfaceView.requestLayout()
        }
    }

    private fun showPin(pin: String?) {
        runOnUiThread {
            if (pin == null) {
                pinView.visibility = View.GONE
                pinView.text = ""
            } else {
                pinView.text = getString(R.string.notification_pin_text, pin)
                pinView.visibility = View.VISIBLE
            }
        }
    }

    /**
     * BACK ends the session. For mirroring the receiver is cycled so the sender is
     * disconnected and "Kutu" re-advertises (master guide section 14, BACK test). For
     * AirPlay Video only the playback is stopped - the sender picks that up from its next
     * `/playback-info` poll, so there is no need to drop the connection.
     *
     * While AirPlay Video plays, the remote also drives transport, because the box is
     * the player here rather than a passive screen.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val svc = service
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (svc != null && svc.videoPlayback.value) {
                svc.stopVideoPlayback()
                endAndFinish(disconnect = false)
            } else {
                endAndFinish(disconnect = true)
            }
            return true
        }
        if (svc != null && svc.videoPlayback.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { svc.togglePlayPause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { svc.togglePlayPause(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { svc.togglePlayPause(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { svc.seekVideoBy(SEEK_STEP_MS); return true }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> { svc.seekVideoBy(-SEEK_STEP_MS); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun endAndFinish(disconnect: Boolean) {
        if (finishing) return
        finishing = true
        val svc = service
        // detach the surface before anything else so no further frame can land on it
        boundSurface?.let { svc?.clearVideoSurface(it) }
        boundSurface = null
        runOnUiThread {
            surfaceView.visibility = View.GONE
            root.setBackgroundColor(Color.BLACK)
        }
        if (disconnect) svc?.endSessionAndRestart()
        finish()
    }

    override fun onDestroy() {
        service?.let {
            it.mirrorSessionCallback = null
            it.videoAspectCallback = null
            it.pinCallback = null
        }
        if (bound) {
            try { unbindService(connection) } catch (_: IllegalArgumentException) {}
            bound = false
        }
        service = null
        super.onDestroy()
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L

        /** started by AirPlayService only */
        fun intent(ctx: Context) = Intent(ctx, MirrorActivity::class.java)
    }
}
