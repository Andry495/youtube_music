package com.youtubevoice.app.dpi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DpiStatus {
    Disconnected,
    Connecting,
    Connected,
    Failed,
}

object DpiController {
    private val _status = MutableStateFlow(DpiStatus.Disconnected)
    val status: StateFlow<DpiStatus> = _status.asStateFlow()

    internal fun setStatus(value: DpiStatus) {
        _status.value = value
    }
}
