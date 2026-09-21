package local.kutu.mirror

import android.media.MediaFormat

/**
 * Preference keys and defaults actually read by the mirroring receiver. Kutu Mirror
 * ships no settings UI, so in practice these are build defaults; the stored file is
 * only written for the fallback MAC address.
 */
object Prefs {
    const val NAME = "settings"
    const val AUTO = "auto"; const val ON = "on"; const val OFF = "off"

    // AirPlay receiver name, as it appears in the iPhone/Mac Screen Mirroring list
    const val SERVER_NAME = "server_name"; const val DEF_SERVER_NAME = "Kutu"
    const val FALLBACK_MAC_ADDRESS = "fallback_mac_address"
    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7000
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true

    // guide 8: H.264 only on this box, matching the tested Android 9 configuration
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = false
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    const val LOW_LATENCY = "low_latency"; const val DEF_LOW_LATENCY = false
    const val OPERATING_RATE = "operating_rate"; const val DEF_OPERATING_RATE = AUTO
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false

    const val AUDIO_AUTO_BUFFER = "audio_auto_buffer"; const val DEF_AUDIO_AUTO_BUFFER = true
    // fixed cushion ms, used only when AUDIO_AUTO_BUFFER is off
    const val AUDIO_CUSHION_MS = "audio_cushion_ms"; const val DEF_AUDIO_CUSHION_MS = 40
    // slider step 0..4 mapping to arrival-delay percentile the cushion targets;
    // lower = less latency, higher = more stable
    const val AUDIO_ADAPTIVE_STEP = "audio_adaptive_step"; const val DEF_AUDIO_ADAPTIVE_STEP = 3
    val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)
    const val OBOE_BUFFER_FRAMES = "oboe_buffer_frames"; const val DEF_OBOE_BUFFER_FRAMES = 0
    // the software ALAC decoder was removed with FFmpeg; never force it
    const val FORCE_SW_ALAC = "force_sw_alac"; const val DEF_FORCE_SW_ALAC = false
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1

    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = AUTO
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false

    // guide 12: PIN support stays available but defaults off. With it off, any device
    // on the same LAN can start mirroring without being challenged.
    const val REQUIRE_PIN = "require_pin"; const val DEF_REQUIRE_PIN = false
    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = true

    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    // opens MirrorActivity when a mirroring session genuinely starts
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = true
}
