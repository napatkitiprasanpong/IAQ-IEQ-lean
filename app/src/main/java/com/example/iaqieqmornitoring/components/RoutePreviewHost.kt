package com.example.iaqieqmornitoring.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.iaqieqmornitoring.utils.LegStatus
import com.example.iaqieqmornitoring.utils.RoutePreview
import com.example.iaqieqmornitoring.utils.RoutePreviewer
import com.example.iaqieqmornitoring.utils.StartGate
import com.example.iaqieqmornitoring.utils.TemiMapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shown before ANY run starts (sudden mission, timed mission, custom routine): the map with the
 * predicted drive, leg by leg, and a warning if a leg looks blocked or too narrow.
 * Placed once at the root of the app, like the routine popup.
 */
@Composable
fun RoutePreviewHost() {
    val pending by StartGate.pending.collectAsState()
    val p = pending ?: return
    val map by TemiMapRepository.state.collectAsState()

    var preview by remember(p) { mutableStateOf<RoutePreview?>(null) }
    var gridUsed by remember(p) { mutableStateOf<com.example.iaqieqmornitoring.utils.MapGrid?>(null) }

    LaunchedEffect(p) {
        val grid = TemiMapRepository.ensureMap()
        val pose = TemiMapRepository.currentPose()
        gridUsed = grid
        preview = withContext(Dispatchers.Default) {
            RoutePreviewer.build(p.title, p.kind, p.routeName, p.returnHome, grid, pose)
        }
    }

    val pv = preview

    Dialog(
        onDismissRequest = { StartGate.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ColorPanel)
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Text("Route preview · ${p.kind.label}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${p.title} · route ${p.routeName}" + (pv?.mapName?.takeIf { it.isNotBlank() }?.let { " · map $it" } ?: ""),
                    color = ColorMuted, fontSize = 13.sp
                )

                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                    MapView(
                        grid = gridUsed ?: map.grid,
                        modifier = Modifier.weight(1.7f).fillMaxHeight(),
                        legs = pv?.legs ?: emptyList(),
                        stops = pv?.stopNames ?: emptyList(),
                        robot = map.robot,
                        emptyText = if (pv == null) "Predicting the route…" else "Map not available"
                    )

                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (pv == null) {
                            Text("Reading the map and planning the drive…", color = ColorMuted, fontSize = 14.sp)
                        } else {
                            Verdict(pv)
                            pv.legs.forEachIndexed { i, leg ->
                                Column(
                                    Modifier.fillMaxWidth().background(Color(0xFF111C33), RoundedCornerShape(12.dp)).padding(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusPill(
                                            when (leg.status) {
                                                LegStatus.CLEAR -> "Clear"
                                                LegStatus.TIGHT -> "Narrow"
                                                LegStatus.BLOCKED -> "Blocked"
                                                LegStatus.UNKNOWN -> "Unknown"
                                            },
                                            legColor(leg.status)
                                        )
                                    }
                                    Text("${i + 1}. ${leg.from} → ${leg.to}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    if (leg.status == LegStatus.CLEAR || leg.status == LegStatus.TIGHT) {
                                        Text("%.1f m · ~%d s".format(leg.distanceM, leg.etaS), color = ColorMuted, fontSize = 12.sp)
                                    }
                                    if (leg.note.isNotBlank()) {
                                        Text(leg.note, color = if (leg.status == LegStatus.BLOCKED) ColorBad else ColorWarn, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (pv.legs.isEmpty()) {
                                Text("Stops: " + pv.stopNames.joinToString(" → "), color = Color.White, fontSize = 13.sp)
                            }
                            pv.messages.forEach { Text(it, color = ColorMuted, fontSize = 11.sp) }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        text = "Cancel",
                        onClick = { StartGate.cancel() },
                        color = Color(0xFF475569),
                        modifier = Modifier.weight(1f).height(54.dp)
                    )
                    ActionButton(
                        text = if (pv?.hasBlocked == true) "Start anyway" else "Start",
                        onClick = { StartGate.confirm() },
                        enabled = pv != null,
                        color = if (pv?.hasBlocked == true) Color(0xFFEF4444) else Color(0xFF10B981),
                        modifier = Modifier.weight(1f).height(54.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Verdict(pv: RoutePreview) {
    val (text, color) = when {
        pv.hasBlocked -> "A drive is predicted to be BLOCKED. temi may not reach every stop." to ColorBad
        !pv.mapAvailable -> "No map: the drive cannot be predicted." to ColorWarn
        pv.legs.isEmpty() -> "The map could not be used to predict the drive." to ColorWarn
        pv.tightCount > 0 -> "${pv.tightCount} narrow passage(s). Clear the way if you can. ${"%.0f".format(pv.totalDistanceM)} m, ${pv.etaText}." to ColorWarn
        pv.hasUnknown -> "Some stops are not on the map. The rest looks clear." to ColorWarn
        else -> "Predicted path is clear: ${"%.0f".format(pv.totalDistanceM)} m, ${pv.etaText}." to ColorOk
    }
    Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}
