package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WindPower
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.ActionButton
import com.example.iaqieqmornitoring.components.ColorAccent
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.IAQConfig
import com.example.iaqieqmornitoring.utils.SensorData
import com.example.iaqieqmornitoring.utils.SensorHistory
import com.example.iaqieqmornitoring.utils.calculateIAQPercentage
import com.example.iaqieqmornitoring.utils.getColorFromLevel
import com.example.iaqieqmornitoring.utils.getLevelFromScore
import kotlin.math.max
import kotlin.math.min

/** CO2, PM and the other "less is better" sensors (the others score best in the middle of their range). */
fun isLowGood(title: String): Boolean {
    return when (title) {
        "CO2", "PM2.5", "PM10", "TVOC", "HCHO" -> true
        else -> false
    }
}

private class GraphSpec(
    /** Key used for the IAQ breakpoints and for the detail screen. */
    val key: String,
    val title: String,
    val unit: String,
    val color: Color,
    val icon: ImageVector,
    val decimals: Int,
    val pick: (SensorData) -> Float
)

private val GRAPHS = listOf(
    GraphSpec("TEMP", "Temperature", "°C", Color(0xFFFF6B6B), Icons.Default.Thermostat, 1) { it.temp },
    GraphSpec("HUMIDITY", "Humidity", "%", Color(0xFF4DABF7), Icons.Default.WaterDrop, 0) { it.humidity },
    GraphSpec("CO2", "CO₂", "ppm", Color(0xFFFFD43B), Icons.Default.Cloud, 0) { it.co2 },
    GraphSpec("PM2.5", "PM2.5", "µg/m³", Color(0xFF22C1DC), Icons.Default.Air, 1) { it.pm25 },
    GraphSpec("PM10", "PM10", "µg/m³", Color(0xFF69DB7C), Icons.Default.WindPower, 1) { it.pm10 },
    GraphSpec("TVOC", "TVOC", "mg/m³", Color(0xFFB197FC), Icons.Default.Science, 2) { it.tvoc },
    GraphSpec("HCHO", "HCHO", "mg/m³", Color(0xFFF783AC), Icons.Default.Biotech, 3) { it.hcho },
    GraphSpec("LIGHT", "Light", "lux", Color(0xFFFFA94D), Icons.Default.LightMode, 0) { it.light },
    GraphSpec("SOUND", "Sound", "dB", Color(0xFF63E6BE), Icons.Default.GraphicEq, 1) { it.sound }
)

private val RANGES = listOf("1 min" to 60_000L, "5 min" to 300_000L, "15 min" to 900_000L, "1 hour" to SensorHistory.MAX_AGE_MS)

@Composable
fun AnalyticsScreen(nav: NavController) {

    val history by SensorHistory.snapshot.collectAsState()
    var rangeIndex by remember { mutableStateOf(1) }
    val rangeMs = RANGES[rangeIndex].second

    // Only the readings inside the chosen window, measured from the newest reading.
    val newest = history.lastOrNull()?.timestampMs ?: 0L
    val window = history.filter { it.timestampMs >= newest - rangeMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {

        TopBar()

        Row(modifier = Modifier.fillMaxSize()) {

            Sidebar(nav)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Analytics", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${window.size} readings · dashed lines are the IAQ breakpoints · tap a graph for details",
                            color = ColorMuted, fontSize = 12.sp
                        )
                    }
                    RANGES.forEachIndexed { i, r ->
                        val on = i == rangeIndex
                        Text(
                            r.first,
                            color = if (on) Color.White else ColorMuted,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(if (on) Color(0xFF2563EB) else Color(0xFF111827), RoundedCornerShape(10.dp))
                                .clickable { rangeIndex = i }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    ActionButton("Clear", onClick = { SensorHistory.clear() }, color = Color(0xFF475569))
                }

                GRAPHS.chunked(3).forEach { rowSpecs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                        rowSpecs.forEach { spec ->
                            SensorGraphCard(spec, window, rangeMs, nav, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorGraphCard(
    spec: GraphSpec,
    window: List<SensorData>,
    rangeMs: Long,
    nav: NavController,
    modifier: Modifier
) {
    val values = window.map { spec.pick(it) }
    val current = values.lastOrNull()
    val lo = values.minOrNull()
    val hi = values.maxOrNull()
    val avg = if (values.isEmpty()) null else values.average().toFloat()
    val breakpoints = IAQConfig.getRange(spec.key)

    val valueColor = if (current == null) ColorMuted else {
        val score = calculateIAQPercentage(current, breakpoints, isLowGood(spec.key))
        getColorFromLevel(getLevelFromScore(score))
    }
    fun fmt(v: Float?) = if (v == null) "–" else "%.${spec.decimals}f".format(v)

    Card(
        modifier = modifier.clickable { nav.navigate("detail/${spec.key}") },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(spec.icon, contentDescription = spec.title, tint = spec.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(spec.title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmt(current), color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(" ${spec.unit}", color = ColorMuted, fontSize = 11.sp)
            }

            Row(Modifier.fillMaxWidth().height(130.dp)) {
                Column(Modifier.height(130.dp).width(40.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(hi), color = ColorMuted, fontSize = 9.sp)
                    Text(fmt(lo), color = ColorMuted, fontSize = 9.sp)
                }
                LineChart(values, spec.color, breakpoints, Modifier.weight(1f).height(130.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("-" + RANGES.firstOrNull { it.second == rangeMs }?.first.orEmpty(), color = ColorMuted, fontSize = 9.sp)
                Text("min ${fmt(lo)} · avg ${fmt(avg)} · max ${fmt(hi)}", color = ColorMuted, fontSize = 10.sp)
                Text("now", color = ColorMuted, fontSize = 9.sp)
            }
        }
    }
}

/** A filled line chart that scales to the data; IAQ breakpoints inside the visible range are drawn dashed. */
@Composable
private fun LineChart(values: List<Float>, color: Color, breakpoints: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        // frame
        drawLine(Color(0xFF1F2937), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        drawLine(Color(0xFF1F2937), Offset(0f, 0f), Offset(0f, size.height), 1f)
        if (values.size < 2) return@Canvas

        // Reduce to about one point per two pixels so a long history stays light to draw.
        val maxPoints = max(2, (size.width / 2f).toInt())
        val step = max(1, values.size / maxPoints)
        val pts = ArrayList<Float>()
        var i = 0
        while (i < values.size) {
            pts.add(values[i]); i += step
        }
        if (pts.last() != values.last()) pts.add(values.last())

        var lo = pts.min()
        var hi = pts.max()
        if (hi - lo < 1e-6f) {
            lo -= 0.5f; hi += 0.5f
        }
        val pad = (hi - lo) * 0.1f
        lo -= pad; hi += pad
        val range = hi - lo

        fun y(v: Float) = size.height - (v - lo) / range * size.height
        fun x(index: Int) = index * size.width / (pts.size - 1)

        for (b in breakpoints) {
            if (b in lo..hi) {
                drawLine(
                    Color(0x55FFFFFF), Offset(0f, y(b)), Offset(size.width, y(b)), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }
        }

        val line = Path()
        val fill = Path()
        pts.forEachIndexed { idx, v ->
            if (idx == 0) {
                line.moveTo(x(idx), y(v)); fill.moveTo(x(idx), size.height); fill.lineTo(x(idx), y(v))
            } else {
                line.lineTo(x(idx), y(v)); fill.lineTo(x(idx), y(v))
            }
        }
        fill.lineTo(x(pts.size - 1), size.height)
        fill.close()

        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 5f, Offset(x(pts.size - 1), y(pts.last())))
    }
}
