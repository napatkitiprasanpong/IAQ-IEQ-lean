package com.example.iaqieqmornitoring.utils

/**
 * Two made-up floor plans for the "Simulated robot" mode, so the Map tab and the route preview
 * can be tried on any tablet. The second map has a closed room (route preview shows BLOCKED)
 * and a narrow (76 cm) door (route preview shows a narrow passage).
 */
object SimMaps {

    private const val RES = 0.05f
    private const val BORDER = 1f

    private class Plan(val widthM: Float, val heightM: Float) {
        val originX = -BORDER
        val originY = -BORDER
        val cols = ((widthM + 2 * BORDER) / RES).toInt()
        val rows = ((heightM + 2 * BORDER) / RES).toInt()
        /** Row 0 = bottom (temi / ROS order): -1 unknown, 0 free, 100 wall. */
        val data = ShortArray(cols * rows) { (-1).toShort() }

        fun fill(x0: Float, y0: Float, x1: Float, y1: Float, v: Int) {
            val c0 = ((x0 - originX) / RES).toInt().coerceIn(0, cols)
            val c1 = ((x1 - originX) / RES).toInt().coerceIn(0, cols)
            val r0 = ((y0 - originY) / RES).toInt().coerceIn(0, rows)
            val r1 = ((y1 - originY) / RES).toInt().coerceIn(0, rows)
            for (r in r0 until r1) for (c in c0 until c1) data[r * cols + c] = v.toShort()
        }

        fun floor() = fill(0f, 0f, widthM, heightM, 0)
        fun wall(x0: Float, y0: Float, x1: Float, y1: Float) = fill(x0, y0, x1, y1, 100)

        /** Outer walls, 15 cm thick. */
        fun shell() {
            val t = 0.15f
            wall(0f, 0f, widthM, t); wall(0f, heightM - t, widthM, heightM)
            wall(0f, 0f, t, heightM); wall(widthM - t, 0f, widthM, heightM)
        }

        fun snapshot(id: String, name: String, locs: List<MapLocation>, walls: List<MapPolyline>) = MapSnapshot(
            mapId = id, name = name, cols = cols, rows = rows, resolution = RES,
            originX = originX, originY = originY, raw = data,
            locations = locs, virtualWalls = walls, greenPaths = emptyList(), simulated = true
        )
    }

    private val ids = listOf("sim-ground", "sim-lab")
    private val names = listOf("Sim · Ground floor", "Sim · Lab wing")

    fun cloudMaps(): List<CloudMap> = ids.indices.map { CloudMap(ids[it], names[it]) }

    fun id(index: Int): String = ids[index.coerceIn(0, ids.size - 1)]

    fun indexOf(id: String): Int = ids.indexOf(id).coerceAtLeast(0)

    fun snapshot(index: Int): MapSnapshot = if (index == 1) labWing() else groundFloor()

    // ---- map 1: a corridor with two labs, both reachable ----
    private fun groundFloor(): MapSnapshot {
        val p = Plan(20f, 14f)
        p.floor(); p.shell()
        val t = 0.15f
        // wall between corridor and labs (y = 6) with 1 m doors at x = 5 and x = 15
        p.wall(0f, 6f, 4.5f, 6f + t); p.wall(5.5f, 6f, 14.5f, 6f + t); p.wall(15.5f, 6f, 20f, 6f + t)
        p.wall(10f, 6f, 10f + t, 14f)              // between Lab A and Lab B
        p.wall(8f, 3.2f, 8.4f, 3.6f)               // a pillar in the corridor
        p.wall(6.5f, 9f, 7.7f, 9.6f)               // a desk in Lab A
        val locs = listOf(
            MapLocation("home base", 1f, 1f),
            MapLocation("Entrance", 2.5f, 3f),
            MapLocation("Corridor", 11f, 3f),
            MapLocation("Lab A", 5f, 10f),
            MapLocation("Lab B", 15f, 10f)
        )
        val walls = listOf(MapPolyline("virtual wall 1", listOf(Pt(18.5f, 0.5f), Pt(18.5f, 5.5f)), 0.1f))
        return p.snapshot(ids[0], names[0], locs, walls)
    }

    // ---- map 2: a hall, an office, a meeting room behind a narrow (76 cm) door and a store with no door ----
    private fun labWing(): MapSnapshot {
        val p = Plan(16f, 12f)
        p.floor(); p.shell()
        val t = 0.15f
        p.wall(0f, 6f, 3.62f, 6f + t); p.wall(4.38f, 6f, 16f, 6f + t)   // 0.76 m door at x = 4 (Meeting room)
        p.wall(10f, 6f, 10f + t, 12f)                                    // Store: closed room, no door
        val locs = listOf(
            MapLocation("home base", 1f, 1f),
            MapLocation("Hall", 4f, 3f),
            MapLocation("Office", 12f, 3f),
            MapLocation("Meeting room", 4f, 9f),
            MapLocation("Store", 13f, 9f)
        )
        return p.snapshot(ids[1], names[1], locs, emptyList())
    }
}
