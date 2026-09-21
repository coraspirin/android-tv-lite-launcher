package local.kutu.mirror.renderer

import local.kutu.mirror.bridge.NativeBridge

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var codecLabel = ""; private set

    private var serverHandle = 0L

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
    }

    @Synchronized
    fun detachEngine() {
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.realtimePriority, config.lowLatency, config.benchmarkLog)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    companion object {
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
