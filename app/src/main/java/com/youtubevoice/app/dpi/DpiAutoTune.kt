package com.youtubevoice.app.dpi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tries ByeDPI presets on-device until [DpiProbe] succeeds.
 * Prefer TUN mode (packet path); SOCKS only if VPN not prepared.
 */
object DpiAutoTune {
    private const val TAG = "DpiAutoTune"

    data class Progress(
        val running: Boolean = false,
        val presetIndex: Int = 0,
        val presetTotal: Int = 0,
        val presetTitle: String = "",
        val message: String = "",
        val successPresetId: String? = null,
    )

    private val mutex = Mutex()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    suspend fun run(context: Context): Progress = mutex.withLock {
        val app = context.applicationContext
        val presets = DpiPresets.ALL
        val previousId = DpiSettingsStore.currentPresetId()
        _progress.value = Progress(
            running = true,
            presetTotal = presets.size,
            message = "Подбор параметров (локальный TUN)…"
        )

        DpiLauncher.startPreferVpn(app)
        delay(1000)

        var lastFail = "не удалось"
        for ((index, preset) in presets.withIndex()) {
            _progress.value = Progress(
                running = true,
                presetIndex = index + 1,
                presetTotal = presets.size,
                presetTitle = preset.title,
                message = "Пробую «${preset.title}» (${index + 1}/${presets.size}, ${DpiLauncher.currentMode()})…"
            )
            Log.i(TAG, "Trying preset ${preset.id} mode=${DpiLauncher.currentMode()}")

            DpiSettingsStore.setPresetId(app, preset.id)
            DpiLauncher.restart(app)
            var waits = 0
            while (DpiController.status.value != DpiStatus.Connected && waits < 40) {
                delay(150)
                waits++
            }
            if (DpiController.status.value != DpiStatus.Connected) {
                lastFail = "туннель не поднялся (${DpiController.status.value})"
                Log.w(TAG, "Not connected after restart for ${preset.id}")
                continue
            }
            delay(400)

            val result = DpiProbe.probeYouTube(timeoutSec = 8L)
            if (result.ok) {
                val done = Progress(
                    running = false,
                    presetIndex = index + 1,
                    presetTotal = presets.size,
                    presetTitle = preset.title,
                    message = "Сохранён пресет «${preset.title}» (${result.label}, ${result.detail})",
                    successPresetId = preset.id,
                )
                _progress.value = done
                Log.i(TAG, "Preset OK: ${preset.id}")
                return done
            }
            lastFail = result.detail
            Log.w(TAG, "Preset fail ${preset.id}: ${result.detail}")
        }

        DpiSettingsStore.setPresetId(app, previousId)
        if (previousId.isNotBlank()) {
            DpiLauncher.restart(app)
        }
        val failed = Progress(
            running = false,
            presetIndex = presets.size,
            presetTotal = presets.size,
            message = "Рабочий пресет не найден. $lastFail. Сохранённый пресет не изменён.",
            successPresetId = null,
        )
        _progress.value = failed
        failed
    }
}
