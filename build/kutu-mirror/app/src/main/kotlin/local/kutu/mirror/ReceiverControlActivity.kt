package local.kutu.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import local.kutu.mirror.service.AirPlayService

/**
 * Starts the receiver, and nothing else. Exported because Kutu Home is a separate
 * package; ADB also uses it for first launch after install.
 *
 * It takes no parameters and accepts no commands: whatever the caller puts in the
 * Intent, the single thing that happens is "start the receiver". It shows nothing and
 * finishes before it can be drawn (master guide section 11).
 *
 * Turning the receiver on here also means "stay on", so the box comes back advertising
 * after a reboot. [ReceiverStopActivity] is the matching off switch.
 */
class ReceiverControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.BOOT_AUTO_START, true).apply()
        val serviceIntent = Intent(this, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(this, serviceIntent)
        setResult(RESULT_OK)
        finish()
    }
}
