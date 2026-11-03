package com.example.iaqieqmornitoring.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.iaqieqmornitoring.components.ActionButton
import com.example.iaqieqmornitoring.components.ColorAccent
import com.example.iaqieqmornitoring.components.ColorBad
import com.example.iaqieqmornitoring.components.ColorField
import com.example.iaqieqmornitoring.components.ColorMuted
import com.example.iaqieqmornitoring.components.ColorOk
import com.example.iaqieqmornitoring.components.ColorWarn
import com.example.iaqieqmornitoring.components.DarkDropdown
import com.example.iaqieqmornitoring.components.LabeledField
import com.example.iaqieqmornitoring.components.SectionCard
import com.example.iaqieqmornitoring.components.Sidebar
import com.example.iaqieqmornitoring.components.Stepper
import com.example.iaqieqmornitoring.components.StatusPill
import com.example.iaqieqmornitoring.components.TopBar
import com.example.iaqieqmornitoring.utils.AppSettings
import com.example.iaqieqmornitoring.utils.DEFAULT_DURATION_S
import com.example.iaqieqmornitoring.utils.DEFAULT_INTERVAL_S
import com.example.iaqieqmornitoring.utils.DURATION_OPTIONS_S
import com.example.iaqieqmornitoring.utils.MissionRoute
import com.example.iaqieqmornitoring.utils.RoutineType
import com.example.iaqieqmornitoring.utils.SheetsUploader
import com.example.iaqieqmornitoring.utils.StopPlan
import com.example.iaqieqmornitoring.utils.intervalsFor
import com.example.iaqieqmornitoring.utils.secondsLabel
import com.example.iaqieqmornitoring.utils.DEFAULT_ROUTINE_MESSAGE
import com.example.iaqieqmornitoring.utils.MissionManager
import com.example.iaqieqmornitoring.utils.Routine
import com.example.iaqieqmornitoring.utils.RoutineManager
import com.example.iaqieqmornitoring.utils.RoutinePhase
import com.example.iaqieqmornitoring.utils.TempMission
import com.example.iaqieqmornitoring.utils.TemiRepository
import com.example.iaqieqmornitoring.utils.MissionStatus
import com.example.iaqieqmornitoring.utils.MissionStopResult
import com.example.iaqieqmornitoring.utils.StopOutcome
import com.example.iaqieqmornitoring.utils.SensorRepository
import com.example.iaqieqmornitoring.utils.StartKind
import com.example.iaqieqmornitoring.utils.StartGate
import com.example.iaqieqmornitoring.utils.PendingStart
import com.example.iaqieqmornitoring.utils.kind
import com.example.iaqieqmornitoring.utils.computeIAQ
import com.example.iaqieqmornitoring.utils.getColorFromLevel
import com.example.iaqieqmornitoring.utils.getLabel
import com.example.iaqieqmornitoring.utils.getLevelFromScore
import com.example.iaqieqmornitoring.components.ToggleRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeText(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

private fun parseTime(text: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

private fun countdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}


private val ColorSelected = Color(0xFF2563EB)

@Composable
fun RoutineScreen(nav: NavController) {

    val routines by AppSettings.routines.collectAsState()
    val routes by AppSettings.routes.collectAsState()
    val selectedRoute by AppSettings.selectedRoute.flow.collectAsState()
    val dwell by AppSettings.dwellSeconds.flow.collectAsState()
    val returnHome by AppSettings.returnHome.flow.collectAsState()
    val snoozeMinutes by AppSettings.routineSnoozeMinutes.flow.collectAsState()
    val promptTimeout by AppSettings.routinePromptTimeoutSeconds.flow.collectAsState()
    val blockAuto by AppSettings.previewBlockAuto.flow.collectAsState()
    val state by RoutineManager.state.collectAsState()
    val mission by MissionManager.state.collectAsState()
    val robot by TemiRepository.state.collectAsState()
    val sensor by SensorRepository.data.collectAsState()
    val link by SensorRepository.link.collectAsState()
    val gateResult by StartGate.result.collectAsState()

    // Ticks once a second so the timers count down on screen.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    var kind by remember { mutableStateOf(StartKind.SUDDEN) }
    var showForm by remember { mutableStateOf(false) }

    val busy = state.isActive || mission.isActive
    val route = routes.firstOrNull { it.name == selectedRoute } ?: routes.firstOrNull()
    val showMissionStatus = mission.isActive || (kind == StartKind.SUDDEN && mission.status != MissionStatus.IDLE)
    val iaq = computeIAQ(sensor)
    val iaqLevel = getLevelFromScore(iaq.toFloat())

    val shownRoutines = routines.filter { it.type.kind == kind }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {

        TopBar()

        Row(modifier = Modifier.fillMaxSize()) {

            Sidebar(nav)

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ------------------------ LEFT: live status ------------------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    SectionCard("Status") {
                        StatusPill(
                            text = when {
                                robot.simulated -> "Simulated robot"
                                robot.ready -> "temi connected"
                                else -> "temi not connected"
                            },
                            color = when {
                                robot.simulated -> ColorWarn
                                robot.ready -> ColorOk
                                else -> ColorBad
                            }
                        )

                        if (showMissionStatus) {
                            val (label, color) = when (mission.status) {
                                MissionStatus.RUNNING -> "Sudden mission running" to ColorAccent
                                MissionStatus.RETURNING -> "Returning to home base" to ColorAccent
                                MissionStatus.COMPLETED -> "Sudden mission complete" to ColorOk
                                MissionStatus.STOPPED -> "Sudden mission stopped" to ColorMuted
                                MissionStatus.FAILED -> "Sudden mission failed" to ColorBad
                                MissionStatus.IDLE -> "Idle" to ColorMuted
                            }
                            StatusPill(label, color)
                            if (mission.routeName.isNotBlank()) {
                                Text(mission.routeName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(mission.message, color = Color.White, fontSize = 14.sp)
                            mission.error?.let { Text(it, color = ColorBad, fontSize = 13.sp) }
                            mission.savedTo?.let { Text("Results saved to:\n$it", color = ColorMuted, fontSize = 11.sp) }
                            if (mission.isActive) {
                                ActionButton(
                                    text = "Stop mission",
                                    onClick = { MissionManager.stop() },
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                )
                            }
                        } else {
                            val (label, color) = when (state.phase) {
                                RoutinePhase.IDLE -> "Idle" to ColorMuted
                                RoutinePhase.GOING -> "Driving" to ColorAccent
                                RoutinePhase.ASKING -> "Waiting for answer" to ColorWarn
                                RoutinePhase.SAMPLING -> "Collecting data" to ColorAccent
                                RoutinePhase.RETURNING -> "Returning to home base" to ColorAccent
                                RoutinePhase.WAITING -> "Waiting at home base" to ColorWarn
                                RoutinePhase.UPLOADING -> "Uploading to Google Sheets" to ColorWarn
                                RoutinePhase.DONE -> "Done" to ColorOk
                                RoutinePhase.STOPPED -> "Stopped" to ColorMuted
                                RoutinePhase.FAILED -> "Failed" to ColorBad
                            }
                            StatusPill(label, color)
                            if (state.routineName.isNotBlank()) {
                                Text(state.routineName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (state.phase != RoutinePhase.IDLE) {
                                Text(state.message, color = Color.White, fontSize = 14.sp)
                            }
                            state.error?.let { Text(it, color = ColorBad, fontSize = 13.sp) }
                            if (state.isActive) {
                                ActionButton(
                                    text = "Stop routine",
                                    onClick = { RoutineManager.stop() },
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                )
                            }
                        }
                        gateResult?.let { Text(it, color = ColorBad, fontSize = 13.sp) }
                    }

                    if (kind == StartKind.SUDDEN) {
                        SectionCard("Live readings") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("IAQ score", color = ColorMuted, fontSize = 13.sp)
                                    Text("$iaq", color = getColorFromLevel(iaqLevel), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                                    Text(getLabel(iaqLevel), color = getColorFromLevel(iaqLevel), fontSize = 14.sp)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Temp  ${"%.1f".format(sensor.temp)} °C", color = Color.White, fontSize = 14.sp)
                                    Text("Humidity  ${"%.0f".format(sensor.humidity)} %", color = Color.White, fontSize = 14.sp)
                                    Text("CO₂  ${"%.0f".format(sensor.co2)} ppm", color = Color.White, fontSize = 14.sp)
                                    Text("PM2.5  ${"%.1f".format(sensor.pm25)} µg/m³", color = Color.White, fontSize = 14.sp)
                                }
                            }
                            Text(link.detail, color = if (link.connected) ColorMuted else ColorWarn, fontSize = 12.sp)
                        }
                    }

                    if (kind == StartKind.TIMED) {
                        SectionCard("Temp missions") {
                            if (state.tempMissions.isEmpty()) {
                                Text(
                                    "None. A temp mission is created each time someone answers “Come back in $snoozeMinutes min”.",
                                    color = ColorMuted,
                                    fontSize = 13.sp
                                )
                            }
                            state.tempMissions.forEach { TempMissionRow(it, nowMs) }
                        }
                    }

                    if (kind != StartKind.SUDDEN) {
                        SectionCard("History") {
                            if (state.log.isEmpty()) {
                                Text("Nothing yet.", color = ColorMuted, fontSize = 13.sp)
                            }
                            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                            state.log.takeLast(12).reversed().forEach {
                                Text(
                                    "${fmt.format(Date(it.timeMs))}  ${it.text}",
                                    color = ColorMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // ------------------------ RIGHT: set up and start ------------------------
                Column(
                    modifier = Modifier
                        .weight(1.3f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    KindSelector(kind) { kind = it; showForm = false }

                    when (kind) {
                        StartKind.SUDDEN -> {
                            SectionCard("Sudden mission") {
                                Text(
                                    "Start now: temi drives the route, waits $dwell s at each stop to sample the air, records the readings " +
                                        "and saves them as a CSV file. Before it leaves you see the predicted route on the map.",
                                    color = ColorMuted, fontSize = 13.sp
                                )
                                Text("Inspection route", color = ColorMuted, fontSize = 13.sp)
                                DarkDropdown(
                                    selected = route?.name ?: "",
                                    options = routes.map { it.name },
                                    onSelect = { AppSettings.selectedRoute.set(it) },
                                    enabled = !busy
                                )
                                Text(
                                    if (route == null || route.waypoints.isEmpty())
                                        "This route has no locations yet. Add them in Settings → Routes."
                                    else
                                        "${route.waypoints.size} stops · ${dwell}s sampling per stop" +
                                            if (returnHome) " · returns to home base" else "",
                                    color = ColorMuted, fontSize = 13.sp
                                )
                                ActionButton(
                                    text = if (mission.isActive) "Stop mission" else "Preview & start",
                                    onClick = {
                                        if (mission.isActive) MissionManager.stop()
                                        else if (route != null) {
                                            val name = route.name
                                            StartGate.request(
                                                PendingStart(StartKind.SUDDEN, "Sudden mission", name, returnHome) {
                                                    MissionManager.start(name)
                                                }
                                            )
                                        }
                                    },
                                    color = if (mission.isActive) Color(0xFFEF4444) else Color(0xFF10B981),
                                    enabled = mission.isActive || (route != null && route.waypoints.isNotEmpty() && robot.ready && !state.isActive),
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                )
                            }

                            val liveRows: List<MissionStopResult> =
                                if (mission.stops.isNotEmpty() && mission.routeName == route?.name) mission.stops
                                else route?.waypoints?.mapIndexed { i, w -> MissionStopResult(i, w, StopOutcome.PENDING) } ?: emptyList()
                            SectionCard(
                                if (mission.stops.isNotEmpty() && mission.routeName == route?.name) "Progress · ${mission.routeName}"
                                else "Planned stops"
                            ) {
                                if (liveRows.isEmpty()) Text("No stops to show.", color = ColorMuted, fontSize = 14.sp)
                                liveRows.forEach { stop ->
                                    StopRow(
                                        stop = stop,
                                        isCurrent = mission.isActive && mission.currentIndex == stop.index,
                                        dwellRemaining = if (mission.currentIndex == stop.index) mission.dwellRemaining else 0
                                    )
                                }
                            }
                        }

                        StartKind.TIMED, StartKind.CUSTOM -> {
                            SectionCard(kind.label + "s") {
                                Text(
                                    if (kind == StartKind.TIMED)
                                        "Every day at the set time temi drives the route and, at each stop, speaks the message and shows a " +
                                            "popup (Yes / Come back in N mins). The app has to be open for it to start."
                                    else
                                        "Every day at the set time temi drives the route and collects sensor data at each stop for the time " +
                                            "you choose per stop, saves it on the tablet and uploads everything to Google Sheets at the end.",
                                    color = ColorMuted, fontSize = 13.sp
                                )

                                if (shownRoutines.isEmpty()) {
                                    Text("No ${kind.label.lowercase()}s yet.", color = Color.White, fontSize = 14.sp)
                                }

                                shownRoutines.forEach { r ->
                                    RoutineRow(
                                        routine = r,
                                        canRun = !state.isActive && !mission.isActive && robot.ready,
                                        onToggle = { on ->
                                            AppSettings.updateRoutines { list ->
                                                list.map { if (it.id == r.id) it.copy(enabled = on) else it }
                                            }
                                        },
                                        onRun = {
                                            StartGate.request(
                                                PendingStart(r.type.kind, r.name, r.routeName, true) { RoutineManager.runNow(r.id) }
                                            )
                                        },
                                        onDelete = {
                                            AppSettings.updateRoutines { list -> list.filter { it.id != r.id } }
                                        }
                                    )
                                }

                                if (!showForm) {
                                    ActionButton(
                                        text = "Add ${kind.label.lowercase()}",
                                        onClick = { showForm = true },
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    )
                                } else {
                                    NewRoutineForm(
                                        kind = kind,
                                        routes = routes,
                                        onCancel = { showForm = false },
                                        onSave = { routine ->
                                            AppSettings.updateRoutines { it + routine }
                                            showForm = false
                                        }
                                    )
                                }
                            }

                            SectionCard("Settings") {
                                if (kind == StartKind.TIMED) {
                                    Stepper(
                                        label = "Come back after",
                                        value = snoozeMinutes,
                                        onChange = { AppSettings.routineSnoozeMinutes.set(it) },
                                        min = 1, max = 60, suffix = " min"
                                    )
                                    Stepper(
                                        label = "Popup waits for an answer",
                                        value = promptTimeout,
                                        onChange = { AppSettings.routinePromptTimeoutSeconds.set(it) },
                                        min = 10, max = 300, step = 10, suffix = " s"
                                    )
                                    Text(
                                        "If nobody answers in time, it counts as “Come back in $snoozeMinutes min”.",
                                        color = ColorMuted, fontSize = 12.sp
                                    )
                                }
                                ToggleRow("Do not start automatically if the predicted route is blocked", blockAuto) {
                                    AppSettings.previewBlockAuto.set(it)
                                }
                                Text(
                                    "“Run now” always shows the route preview first. Locations, home base, retries and the navigation " +
                                        "timeout come from Settings.",
                                    color = ColorMuted, fontSize = 12.sp
                                )
                            }

                            if (kind == StartKind.CUSTOM) SheetsCard()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindSelector(kind: StartKind, onSelect: (StartKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorField, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StartKind.values().forEach { k ->
            val on = k == kind
            Text(
                k.label,
                color = if (on) Color.White else ColorMuted,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) ColorSelected else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onSelect(k) }
                    .padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun StopRow(stop: MissionStopResult, isCurrent: Boolean, dwellRemaining: Int) {

    val color = when (stop.outcome) {
        StopOutcome.PENDING -> Color.Gray
        StopOutcome.NAVIGATING -> ColorAccent
        StopOutcome.SAMPLING -> ColorWarn
        StopOutcome.DONE -> ColorOk
        StopOutcome.FAILED -> ColorBad
    }
    val label = when (stop.outcome) {
        StopOutcome.PENDING -> "Pending"
        StopOutcome.NAVIGATING -> "Driving…"
        StopOutcome.SAMPLING -> if (dwellRemaining > 0) "Sampling ${dwellRemaining}s" else "Sampling"
        StopOutcome.DONE -> "Done"
        StopOutcome.FAILED -> "Failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Color(0xFF111C33) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(color.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("${stop.index + 1}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stop.location, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val r = stop.readings
            if (stop.outcome == StopOutcome.DONE && r != null) {
                Text(
                    "IAQ ${stop.iaq} · ${"%.1f".format(r.temp)} °C · ${"%.0f".format(r.humidity)} % · " +
                        "CO₂ ${"%.0f".format(r.co2)} · PM2.5 ${"%.1f".format(r.pm25)}",
                    color = ColorMuted, fontSize = 12.sp
                )
            }
            if (stop.note.isNotBlank()) {
                Text(stop.note, color = if (stop.outcome == StopOutcome.FAILED) ColorBad else ColorWarn, fontSize = 12.sp)
            }
        }
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TempMissionRow(t: TempMission, nowMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(t.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "→ ${t.location}" + if (t.round > 1) " · round ${t.round}" else "",
                color = ColorMuted,
                fontSize = 12.sp
            )
        }
        Text(
            if (t.dueAtMs == 0L) "timer starts at home base" else "in ${countdown(t.dueAtMs - nowMs)}",
            color = ColorWarn,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    canRun: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            timeText(routine.hour, routine.minute),
            color = if (routine.enabled) ColorAccent else ColorMuted,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                routine.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                routine.type.kind.label + " · Route: ${routine.routeName.ifBlank { "—" }}",
                color = ColorMuted,
                fontSize = 12.sp
            )
            if (routine.type == RoutineType.IAQ_IEQ) {
                Text(
                    routine.plans.joinToString(" · ") {
                        "${it.location} ${secondsLabel(it.durationS)}/${secondsLabel(it.intervalS)}"
                    }.ifBlank { "—" },
                    color = ColorMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("“${routine.message}”", color = ColorMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(
            checked = routine.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ColorAccent)
        )
        Spacer(Modifier.width(10.dp))
        ActionButton(text = "Preview & run", onClick = onRun, enabled = canRun, color = Color(0xFF10B981))
        Spacer(Modifier.width(8.dp))
        ActionButton(text = "Delete", onClick = onDelete, color = Color(0xFFEF4444))
    }
}

@Composable
private fun NewRoutineForm(
    kind: StartKind,
    routes: List<MissionRoute>,
    onCancel: () -> Unit,
    onSave: (Routine) -> Unit
) {
    val type = if (kind == StartKind.CUSTOM) RoutineType.IAQ_IEQ else RoutineType.FREE_STYLE
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("08:00") }
    var route by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(DEFAULT_ROUTINE_MESSAGE) }

    val routeNames = routes.map { it.name }
    val chosenRoute = route.ifBlank { routeNames.firstOrNull() ?: "" }
    val waypoints = routes.firstOrNull { it.name == chosenRoute }?.waypoints ?: emptyList()
    val parsed = parseTime(time)

    // One plan (duration + interval) per stop of the chosen route; reset when another route is picked.
    val plans = remember(chosenRoute, waypoints) {
        waypoints.map { StopPlan(it, DEFAULT_DURATION_S, DEFAULT_INTERVAL_S) }.toMutableStateList()
    }
    val isIaq = type == RoutineType.IAQ_IEQ

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorField, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("New ${kind.label.lowercase()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        LabeledField(label = "Name (optional)", value = name, onChange = { name = it }, modifier = Modifier.fillMaxWidth())
        LabeledField(
            label = "Time every day (24 h, HH:MM)",
            value = time,
            onChange = { time = it },
            isError = parsed == null,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Route", color = ColorMuted, fontSize = 13.sp)
        DarkDropdown(selected = chosenRoute, options = routeNames, onSelect = { route = it })

        if (!isIaq) {
            LabeledField(
                label = "What temi says and shows at each stop",
                value = message,
                onChange = { message = it },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("Data collection at each stop", color = ColorMuted, fontSize = 13.sp)
            if (plans.isEmpty()) {
                Text("This route has no locations yet. Add some in Settings.", color = ColorWarn, fontSize = 12.sp)
            }
            plans.forEachIndexed { i, plan ->
                PlanRow(index = i + 1, plan = plan, onChange = { plans[i] = it })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = "Save",
                enabled = parsed != null && chosenRoute.isNotBlank() && (!isIaq || plans.isNotEmpty()),
                onClick = {
                    val (h, m) = parsed ?: return@ActionButton
                    val defaultName = (if (isIaq) "Custom " else "Timed ") + timeText(h, m)
                    onSave(
                        Routine(
                            id = System.currentTimeMillis().toString(),
                            name = name.trim().ifBlank { defaultName },
                            hour = h,
                            minute = m,
                            routeName = chosenRoute,
                            message = message.trim().ifBlank { DEFAULT_ROUTINE_MESSAGE },
                            type = type,
                            plans = if (isIaq) plans.toList() else emptyList()
                        )
                    )
                },
                color = Color(0xFF10B981)
            )
            ActionButton(text = "Cancel", onClick = onCancel, color = Color(0xFF475569))
        }
    }
}

/** Duration + interval pickers for one stop of an IAQ-IEQ routine. */
@Composable
private fun PlanRow(index: Int, plan: StopPlan, onChange: (StopPlan) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C33), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$index. ${plan.location}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Collect for", color = ColorMuted, fontSize = 12.sp)
                DarkDropdown(
                    selected = secondsLabel(plan.durationS),
                    options = DURATION_OPTIONS_S.map { secondsLabel(it) },
                    onSelect = { label ->
                        val d = DURATION_OPTIONS_S.firstOrNull { secondsLabel(it) == label } ?: plan.durationS
                        val allowed = intervalsFor(d)
                        onChange(plan.copy(durationS = d, intervalS = if (plan.intervalS in allowed) plan.intervalS else allowed.last()))
                    }
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reading every", color = ColorMuted, fontSize = 12.sp)
                DarkDropdown(
                    selected = secondsLabel(plan.intervalS),
                    options = intervalsFor(plan.durationS).map { secondsLabel(it) },
                    onSelect = { label ->
                        val iv = intervalsFor(plan.durationS).firstOrNull { secondsLabel(it) == label } ?: plan.intervalS
                        onChange(plan.copy(intervalS = iv))
                    }
                )
            }
        }
        Text("→ ${plan.sampleCount} readings at this stop", color = ColorMuted, fontSize = 12.sp)
    }
}

/** Google Sheets connection (URL + token), connection test and manual upload of data still on the tablet. */
@Composable
private fun SheetsCard() {
    val url by AppSettings.sheetsUrl.flow.collectAsState()
    val token by AppSettings.sheetsToken.flow.collectAsState()
    val up by SheetsUploader.state.collectAsState()
    val routine by RoutineManager.state.collectAsState()

    LaunchedEffect(Unit) { SheetsUploader.refreshPending() }

    SectionCard("Google Sheets upload (IAQ-IEQ routine)") {
        Text(
            "Paste the web app URL from the Google Apps Script (apps_script/Code.gs) and the same secret token " +
                "you put in that script. Readings go to the “iaq-ieq data” folder in your Google Drive.",
            color = ColorMuted,
            fontSize = 12.sp
        )
        LabeledField(
            label = "Apps Script web app URL",
            value = url,
            onChange = { AppSettings.sheetsUrl.set(it.trim()) },
            modifier = Modifier.fillMaxWidth()
        )
        LabeledField(
            label = "Secret token",
            value = token,
            onChange = { AppSettings.sheetsToken.set(it.trim()) },
            password = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                text = "Test connection",
                onClick = { SheetsUploader.testAsync() },
                enabled = !up.busy && url.isNotBlank(),
                color = Color(0xFF475569)
            )
            ActionButton(
                text = "Upload pending now",
                onClick = { SheetsUploader.uploadNowAsync() },
                enabled = !up.busy && url.isNotBlank() && up.pendingFiles > 0 && !routine.isActive,
                color = Color(0xFF10B981)
            )
        }
        Text(
            "Waiting on the tablet: ${up.pendingFiles} file(s)",
            color = if (up.pendingFiles > 0) ColorWarn else ColorMuted,
            fontSize = 12.sp
        )
        if (up.message.isNotBlank()) {
            Text(
                up.message,
                color = when (up.ok) { true -> ColorOk; false -> ColorBad; null -> ColorMuted },
                fontSize = 12.sp
            )
        }
    }
}
