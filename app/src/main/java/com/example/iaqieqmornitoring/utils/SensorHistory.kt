package com.example.iaqieqmornitoring.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the last hour of readings in memory (every reading that arrives, whichever screen is open),
 * so the Analytics graphs do not start empty. It is cleared when the app process ends.
 */
object SensorHistory {

    const val MAX_AGE_MS = 60L * 60_000L
    private const val MAX_POINTS = 4000

    private val buffer = ArrayList<SensorData>()
    private val _snapshot = MutableStateFlow<List<SensorData>>(emptyList())
    val snapshot: StateFlow<List<SensorData>> = _snapshot.asStateFlow()

    @Synchronized
    fun add(d: SensorData) {
        buffer.add(d)
        val cutoff = d.timestampMs - MAX_AGE_MS
        var drop = 0
        while (drop < buffer.size && buffer[drop].timestampMs < cutoff) drop++
        if (buffer.size - drop > MAX_POINTS) drop = buffer.size - MAX_POINTS
        if (drop > 0) buffer.subList(0, drop).clear()
        _snapshot.value = ArrayList(buffer)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _snapshot.value = emptyList()
    }
}

/** Reads one sensor by the names used in the app ("CO2", "PM2.5", "Temperature", ...). Null if unknown. */
fun SensorData.valueOf(name: String): Float? = when (name.uppercase()) {
    "TEMP", "TEMPERATURE" -> temp
    "HUMID", "HUMIDITY" -> humidity
    "CO2" -> co2
    "PM2.5" -> pm25
    "PM10" -> pm10
    "TVOC" -> tvoc
    "HCHO" -> hcho
    "LIGHT" -> light
    "SOUND" -> sound
    else -> null
}
