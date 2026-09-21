package local.kutu.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import local.kutu.mirror.service.AirPlayService

/**
 * Stops the receiver, and nothing else. The mirror-off half of [ReceiverControlActivity].
 *
 * Same contract as its counterpart: exported so Kutu Home can reach it, no parameters,
 * no commands, no UI, finishes before it can be drawn. Stopping also clears the
 * boot auto-start flag, so "off" survives a reboot instead of silently coming back.
 *
 * Master guide section 11 describes only a start shim. Being exported, this one lets
 * any app on the box stop the receiver, which is a nuisance at worst and is recorded
 * as a disclosed deviation in KUTU-MIRROR.md.
 */
class ReceiverStopActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.BOOT_AUTO_START, false).apply()
        // no-op when the service is not running
        stopService(Intent(this, AirPlayService::class.java))
        setResult(RESULT_OK)
        finish()
    }
}
