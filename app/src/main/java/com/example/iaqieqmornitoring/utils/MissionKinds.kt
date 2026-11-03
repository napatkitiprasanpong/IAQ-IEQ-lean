package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The three kinds of run in the Routine tab:
 *  - Sudden mission: start now on a route, sample at every stop, save a CSV (the old "Mission").
 *  - Timed mission: every day at a set time, drive the route and ask a question at each stop (the old "Routine").
 *  - Custom routine: timed, with its own sampling time / interval per stop, uploaded to Google Sheets (the old "IAQ-IEQ routine").
 */
enum class StartKind(val label: String) {
    SUDDEN("Sudden mission"),
    TIMED("Timed mission"),
    CUSTOM("Custom routine")
}

val RoutineType.kind: StartKind get() = if (this == RoutineType.IAQ_IEQ) StartKind.CUSTOM else StartKind.TIMED

val RoutineType.label: String get() = kind.label

/** A run waiting for the person to look at the predicted route and press Start. */
class PendingStart(
    val kind: StartKind,
    val title: String,
    val routeName: String,
    val returnHome: Boolean,
    /** Starts the run; returns an error message, or null if it started. */
    val onConfirm: () -> String?
)

/**
 * Every manual start goes through here: the route preview is shown first (see RoutePreviewHost),
 * and the run only starts when the person confirms it.
 */
object StartGate {
    private val _pending = MutableStateFlow<PendingStart?>(null)
    val pending: StateFlow<PendingStart?> = _pending.asStateFlow()

    /** Result of the last confirmed start (an error text, or null when it started fine). */
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    fun request(p: PendingStart) {
        _result.value = null
        _pending.value = p
    }

    fun confirm() {
        val p = _pending.value ?: return
        _pending.value = null
        _result.value = p.onConfirm()
    }

    fun cancel() {
        _pending.value = null
    }
}
