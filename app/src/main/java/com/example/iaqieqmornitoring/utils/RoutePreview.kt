package com.example.iaqieqmornitoring.utils

import kotlin.math.roundToInt

data class RoutePreview(
    val title: String,
    val kind: StartKind,
    val routeName: String,
    val mapName: String,
    val mapAvailable: Boolean,
    val stopNames: List<String>,
    val legs: List<LegPlan>,
    val messages: List<String>
) {
    val totalDistanceM: Float get() = legs.sumOf { it.distanceM.toDouble() }.toFloat()
    val totalEtaS: Int get() = legs.sumOf { it.etaS }
    val hasBlocked: Boolean get() = legs.any { it.status == LegStatus.BLOCKED }
    val tightCount: Int get() = legs.count { it.status == LegStatus.TIGHT }
    val hasUnknown: Boolean get() = !mapAvailable || legs.any { it.status == LegStatus.UNKNOWN }

    val etaText: String
        get() {
            val m = (totalEtaS / 60f).roundToInt()
            return if (m < 1) "under 1 min" else "about $m min"
        }
}

/** Builds the route preview: where temi starts, every stop, home base, and the predicted path of each drive. */
object RoutePreviewer {

    fun build(
        title: String,
        kind: StartKind,
        routeName: String,
        returnHome: Boolean,
        grid: MapGrid?,
        robotPose: RobotPose?
    ): RoutePreview {
        val route = AppSettings.routes.value.firstOrNull { it.name == routeName }
        val waypoints = route?.waypoints ?: emptyList()
        val homeName = AppSettings.homeBase.value.ifBlank { "home base" }
        val messages = ArrayList<String>()

        if (grid == null) {
            messages += "The map is not available, so the drive cannot be predicted. Showing the order of the stops only."
            return RoutePreview(title, kind, routeName, "", false, waypoints + (if (returnHome) listOf(homeName) else emptyList()), emptyList(), messages)
        }

        val snap = grid.snapshot
        if (!grid.reliable) {
            messages += "The map could not be read reliably (${grid.description}). Check it in the Map tab; no path is predicted."
            return RoutePreview(title, kind, routeName, snap.name, true, waypoints + (if (returnHome) listOf(homeName) else emptyList()), emptyList(), messages)
        }

        // Home base: the saved location, or the map origin (temi's map is reset on the charging station).
        val homeLoc = snap.location(homeName)
        val homePt: Pt? = when {
            homeLoc != null -> Pt(homeLoc.x, homeLoc.y)
            homeName.equals("home base", ignoreCase = true) -> {
                messages += "Home base is not listed on the map; assuming it is at the map origin."
                Pt(0f, 0f)
            }
            else -> null
        }

        val stops = ArrayList<RouteStop>()
        if (robotPose != null) stops += RouteStop("Current position", Pt(robotPose.x, robotPose.y))
        else stops += RouteStop(homeName, homePt)
        for (w in waypoints) {
            val l = snap.location(w)
            stops += RouteStop(w, l?.let { Pt(it.x, it.y) })
        }
        if (returnHome) stops += RouteStop(homeName, homePt)

        val planGrid = PathPlanner.buildGrid(grid)
        val legs = PathPlanner.planRoute(planGrid, stops)

        if (kind == StartKind.TIMED) messages += "Extra visits for \"Come back in N mins\" answers are not shown."
        messages += "Safety margin ${(PlannerConfig.INFLATION_M * 100).roundToInt()} cm around walls. " +
            "People and objects that are not on the map cannot be predicted; temi's sensors still stop it."
        return RoutePreview(title, kind, routeName, snap.name, true, stops.map { it.name }, legs, messages)
    }

    /** For automatic starts (no screen to show the preview on): the same prediction, using the cached map. */
    suspend fun buildForAutoStart(kind: StartKind, routeName: String, returnHome: Boolean): RoutePreview {
        val grid = TemiMapRepository.ensureMap()
        val pose = TemiMapRepository.currentPose()
        return build(kind.label, kind, routeName, returnHome, grid, pose)
    }
}
