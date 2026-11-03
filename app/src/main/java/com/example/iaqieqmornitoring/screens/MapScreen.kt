package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.ActionButton
import com.example.iaqieqmornitoring.components.ColorBad
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.ColorOk
import com.example.iaqieqmornitoring.components.ColorPanel
import com.example.iaqieqmornitoring.components.ColorWarn
import com.example.iaqieqmornitoring.components.DarkDropdown
import com.example.iaqieqmornitoring.components.MapView
import com.example.iaqieqmornitoring.components.SectionCard
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.StatusPill
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.MissionManager
import com.example.iaqieqmornitoring.utils.RoutineManager
import com.example.iaqieqmornitoring.utils.TemiMapRepository
import com.example.iaqieqmornitoring.utils.TemiRepository
import kotlinx.coroutines.delay

private val ORIENTATION_OPTIONS = listOf("Auto", "Bottom-up", "Top-down")
private val INVERT_OPTIONS = listOf("Auto", "Normal", "Swap free / walls")

@Composable
fun MapScreen(nav: NavController) {

    val map by TemiMapRepository.state.collectAsState()
    val robot by TemiRepository.state.collectAsState()
    val routes by AppSettings.routes.collectAsState()
    val orientation by AppSettings.mapOrientation.flow.collectAsState()
    val invert by AppSettings.mapInvert.flow.collectAsState()
    val mission by MissionManager.state.collectAsState()
    val routine by RoutineManager.state.collectAsState()

    var selected by remember { mutableStateOf<String?>(null) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var permissionNote by remember { mutableStateOf("") }

    // Read the map when the tab opens, then keep the robot's position fresh.
    LaunchedEffect(Unit) {
        if (map.grid == null) TemiMapRepository.refresh()
        while (true) {
            TemiMapRepository.pollPosition()
            delay(1500)
        }
    }

    val grid = map.grid
    val snap = grid?.snapshot
    val busy = mission.isActive || routine.isActive
    val currentName = map.maps.firstOrNull { it.id == map.currentMapId }?.name ?: snap?.name ?: ""

    Column(Modifier.fillMaxSize().background(Color(0xFF020617))) {
        TopBar()
        Row(Modifier.fillMaxSize()) {
            Sidebar(nav)

            Row(
                Modifier.weight(1f).fillMaxHeight().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ------------------------ LEFT: maps, locations, display ------------------------
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionCard("Map") {
                        StatusPill(
                            text = when {
                                map.loading -> "Loading…"
                                grid != null -> map.source.ifBlank { "Map loaded" }
                                else -> "No map"
                            },
                            color = when {
                                map.loading -> ColorWarn
                                robot.simulated -> ColorWarn
                                grid != null -> ColorOk
                                else -> ColorBad
                            }
                        )
                        Text(
                            "Maps are the ones backed up on the temi cloud. Choosing another map makes temi load it " +
                                "(it must be on its charger), and its saved locations change with it.",
                            color = ColorMuted, fontSize = 12.sp
                        )

                        Text("Map", color = ColorMuted, fontSize = 13.sp)
                        DarkDropdown(
                            selected = currentName,
                            options = map.maps.map { it.name },
                            onSelect = { name ->
                                val m = map.maps.firstOrNull { it.name == name }
                                if (m != null && m.id != map.currentMapId) switchTo = m.id
                            },
                            placeholder = "No maps found",
                            enabled = !busy && !map.loading
                        )
                        if (busy) Text("Stop the running mission / routine to change the map.", color = ColorWarn, fontSize = 12.sp)

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("Refresh from temi", onClick = { TemiMapRepository.refresh() }, enabled = !map.loading)
                            if (map.needsPermission && !robot.simulated) {
                                ActionButton("Allow map access", onClick = { permissionNote = TemiMapRepository.requestMapPermission() },
                                    color = Color(0xFF2563EB))
                            }
                        }
                        if (map.error.isNotBlank()) Text(map.error, color = ColorBad, fontSize = 13.sp)
                        else if (map.message.isNotBlank()) Text(map.message, color = ColorMuted, fontSize = 12.sp)
                        if (permissionNote.isNotBlank()) Text(permissionNote, color = ColorWarn, fontSize = 12.sp)
                    }

                    SectionCard("Locations (${snap?.locations?.size ?: 0})") {
                        if (snap == null || snap.locations.isEmpty()) {
                            Text("No locations on this map. Save locations on temi, then refresh.", color = ColorMuted, fontSize = 13.sp)
                        }
                        snap?.locations?.forEach { l ->
                            val inRoutes = routes.filter { r -> r.waypoints.any { it.equals(l.name, true) } }.map { it.name }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (l.name.equals(selected, true)) Color(0xFF1E3A5F) else Color(0xFF111C33), RoundedCornerShape(12.dp))
                                    .clickable { selected = if (l.name.equals(selected, true)) null else l.name }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("x %.2f · y %.2f m".format(l.x, l.y), color = ColorMuted, fontSize = 11.sp)
                                }
                                if (inRoutes.isNotEmpty()) Text(inRoutes.joinToString(", "), color = ColorMuted, fontSize = 11.sp)
                            }
                        }
                        // routes that point at locations this map does not have
                        if (snap != null) {
                            routes.forEach { r ->
                                val missing = r.waypoints.filter { snap.location(it) == null }
                                if (missing.isNotEmpty()) {
                                    Text("Route ${r.name} uses locations not on this map: ${missing.joinToString(", ")}", color = ColorWarn, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    SectionCard("Map display") {
                        grid?.let { Text("Read as: ${it.description}", color = ColorMuted, fontSize = 12.sp) }
                        Text(
                            "The app works out how temi's map data is laid out. If the map looks upside down or inverted, " +
                                "or the route preview says it cannot read the map, override it here.",
                            color = ColorMuted, fontSize = 12.sp
                        )
                        Text("Row order", color = ColorMuted, fontSize = 13.sp)
                        DarkDropdown(
                            selected = ORIENTATION_OPTIONS[orientation.coerceIn(0, 2)],
                            options = ORIENTATION_OPTIONS,
                            onSelect = {
                                AppSettings.mapOrientation.set(ORIENTATION_OPTIONS.indexOf(it))
                                TemiMapRepository.rebuildGrid()
                            }
                        )
                        Text("Free space / walls", color = ColorMuted, fontSize = 13.sp)
                        DarkDropdown(
                            selected = INVERT_OPTIONS[invert.coerceIn(0, 2)],
                            options = INVERT_OPTIONS,
                            onSelect = {
                                AppSettings.mapInvert.set(INVERT_OPTIONS.indexOf(it))
                                TemiMapRepository.rebuildGrid()
                            }
                        )
                    }
                }

                // ------------------------ RIGHT: the map ------------------------
                Column(Modifier.weight(2.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MapView(
                        grid = grid,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        robot = map.robot,
                        navPath = map.navPath,
                        selected = selected,
                        onSelect = { selected = it },
                        emptyText = if (map.loading) "Loading the map…" else "No map yet. Tap Refresh from temi."
                    )
                    Text(
                        "Green dot = temi · grey dots = saved locations · red lines = virtual walls · orange dashes = the path temi is driving now. " +
                            "Drag to move, pinch or +/− to zoom, tap a location to highlight it.",
                        color = ColorMuted, fontSize = 11.sp
                    )
                }
            }
        }
    }

    switchTo?.let { id ->
        val name = map.maps.firstOrNull { it.id == id }?.name ?: id
        AlertDialog(
            onDismissRequest = { switchTo = null },
            containerColor = ColorPanel,
            title = { Text("Switch temi to \"$name\"?", color = Color.White) },
            text = {
                Text(
                    "temi will load this map (it must be on its charger and online). Saved locations change, so routes may " +
                        "refer to places that no longer exist. This changes the map of the whole robot.",
                    color = ColorMuted
                )
            },
            confirmButton = {
                TextButton(onClick = { TemiMapRepository.switchMap(id); switchTo = null }) { Text("Load map", color = ColorOk) }
            },
            dismissButton = {
                TextButton(onClick = { switchTo = null }) { Text("Cancel", color = ColorMuted) }
            }
        )
    }
}
