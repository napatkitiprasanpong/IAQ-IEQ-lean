package com.example.iaqieqmornitoring.utils

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Plain data types for the temi map (no Android / temi SDK types, so they are easy to test)
// ---------------------------------------------------------------------------------------------

/** A point in temi's map frame, in metres (x right, y up, (0,0) = where the map was reset, i.e. home base). */
data class Pt(val x: Float, val y: Float)

data class MapLocation(val name: String, val x: Float, val y: Float, val theta: Float = 0f)

data class MapPolyline(val id: String, val points: List<Pt>, val thickness: Float)

/** A map backed up on the temi cloud (what `getMapList()` returns). */
data class CloudMap(val id: String, val name: String)

data class RobotPose(val x: Float, val y: Float, val yaw: Float)

/**
 * The map of the robot as it was read from temi: the raw occupancy matrix plus locations and layers.
 * [raw] is row-major exactly as temi sent it; [MapGrid] turns it into free / occupied / unknown cells.
 */
class MapSnapshot(
    val mapId: String,
    val name: String,
    val cols: Int,
    val rows: Int,
    /** Metres per cell. */
    val resolution: Float,
    val originX: Float,
    val originY: Float,
    val raw: ShortArray,
    val locations: List<MapLocation>,
    val virtualWalls: List<MapPolyline>,
    val greenPaths: List<MapPolyline>,
    val simulated: Boolean = false
) {
    fun location(name: String): MapLocation? {
        val n = name.trim()
        return locations.firstOrNull { it.name.equals(n, ignoreCase = true) }
    }
}

object Cell {
    const val UNKNOWN: Byte = 0
    const val FREE: Byte = 1
    const val OCCUPIED: Byte = 2
}

/** How the numbers in the occupancy matrix are to be read. Detected automatically, can be overridden. */
enum class CellEncoding(val label: String) {
    OCCUPANCY("0 = free, 100 = wall"),
    LIGHT_FREE("light = free, dark = wall"),
    DARK_FREE("dark = free, light = wall")
}

data class WorldBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}

/**
 * The map as free / occupied / unknown cells, stored with row 0 at the BOTTOM (world y = originY), so a
 * cell index is `row * cols + col` with `col = (x - originX) / resolution` and `row = (y - originY) / resolution`.
 */
class MapGrid(
    val snapshot: MapSnapshot,
    val cells: ByteArray,
    val encoding: CellEncoding,
    /** True if temi's data starts at the bottom row (ROS convention). */
    val rowZeroAtBottom: Boolean,
    val inverted: Boolean,
    /** Share of saved locations that lie on free cells; null if there are no locations to check against. */
    val confidence: Float?,
    val bounds: WorldBounds
) {
    val cols: Int get() = snapshot.cols
    val rows: Int get() = snapshot.rows

    fun colOf(x: Float): Int = floor((x - snapshot.originX) / snapshot.resolution).toInt()
    fun rowOf(y: Float): Int = floor((y - snapshot.originY) / snapshot.resolution).toInt()

    /** True if the map could be read well enough to predict paths on it. */
    val reliable: Boolean get() = confidence == null || confidence >= 0.5f

    val description: String
        get() = "${encoding.label}" + (if (inverted) " (inverted)" else "") +
            ", " + (if (rowZeroAtBottom) "bottom-up" else "top-down") +
            (confidence?.let { " · ${(it * 100).toInt()}% of locations on free space" } ?: "")

    companion object {

        private fun classify(v: Int, enc: CellEncoding): Byte {
            if (v < 0) return Cell.UNKNOWN
            return when (enc) {
                CellEncoding.OCCUPANCY -> when {
                    v <= 25 -> Cell.FREE
                    v >= 65 -> Cell.OCCUPIED
                    else -> Cell.UNKNOWN
                }
                CellEncoding.LIGHT_FREE -> when {
                    v >= 240 -> Cell.FREE
                    v <= 100 -> Cell.OCCUPIED
                    else -> Cell.UNKNOWN
                }
                CellEncoding.DARK_FREE -> when {
                    v <= 15 -> Cell.FREE
                    v >= 200 -> Cell.OCCUPIED
                    else -> Cell.UNKNOWN
                }
            }
        }

        private fun rawIndex(s: MapSnapshot, col: Int, row: Int, bottomUp: Boolean): Int {
            val r = if (bottomUp) row else s.rows - 1 - row
            return r * s.cols + col
        }

        /** Is any cell within 2 cells of (col,row) free under this interpretation? */
        private fun freeNear(s: MapSnapshot, col: Int, row: Int, enc: CellEncoding, bottomUp: Boolean): Boolean {
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val c = col + dc
                    val r = row + dr
                    if (c < 0 || r < 0 || c >= s.cols || r >= s.rows) continue
                    if (classify(s.raw[rawIndex(s, c, r, bottomUp)].toInt(), enc) == Cell.FREE) return true
                }
            }
            return false
        }

        private fun score(s: MapSnapshot, enc: CellEncoding, bottomUp: Boolean): Int {
            var hits = 0
            for (l in s.locations) {
                val c = floor((l.x - s.originX) / s.resolution).toInt()
                val r = floor((l.y - s.originY) / s.resolution).toInt()
                if (freeNear(s, c, r, enc, bottomUp)) hits++
            }
            return hits
        }

        /**
         * @param orientation 0 = detect, 1 = data starts at the bottom row, 2 = data starts at the top row
         * @param invert 0 = detect, 1 = normal, 2 = swap free and wall
         */
        fun build(s: MapSnapshot, orientation: Int = 0, invert: Int = 0): MapGrid {
            var minV = Int.MAX_VALUE
            var maxV = Int.MIN_VALUE
            for (v in s.raw) {
                val i = v.toInt()
                if (i < minV) minV = i
                if (i > maxV) maxV = i
            }
            val defaultEnc = if (minV < 0 || maxV <= 100) CellEncoding.OCCUPANCY else CellEncoding.LIGHT_FREE

            // Every way of reading the matrix, the most likely first. The one that puts the most of temi's
            // saved locations on free floor wins (a location inside a wall is obviously the wrong reading).
            val encodings = listOf(defaultEnc) + CellEncoding.values().filter { it != defaultEnc }
            val orientations = when (orientation) {
                1 -> listOf(true)
                2 -> listOf(false)
                else -> listOf(true, false)
            }
            var bestEnc = defaultEnc
            var bestBottomUp = orientations.first()
            var bestScore = -1
            if (s.locations.isNotEmpty()) {
                for (enc in encodings) for (bu in orientations) {
                    val sc = score(s, enc, bu)
                    if (sc > bestScore) {
                        bestScore = sc; bestEnc = enc; bestBottomUp = bu
                    }
                }
            }
            val swap = invert == 2

            val cells = ByteArray(s.cols * s.rows)
            var minC = Int.MAX_VALUE
            var minR = Int.MAX_VALUE
            var maxC = Int.MIN_VALUE
            var maxR = Int.MIN_VALUE
            for (row in 0 until s.rows) {
                for (col in 0 until s.cols) {
                    var t = classify(s.raw[rawIndex(s, col, row, bestBottomUp)].toInt(), bestEnc)
                    if (swap) t = when (t) {
                        Cell.FREE -> Cell.OCCUPIED
                        Cell.OCCUPIED -> Cell.FREE
                        else -> t
                    }
                    cells[row * s.cols + col] = t
                    if (t != Cell.UNKNOWN) {
                        if (col < minC) minC = col
                        if (col > maxC) maxC = col
                        if (row < minR) minR = row
                        if (row > maxR) maxR = row
                    }
                }
            }

            var bx0 = s.originX
            var by0 = s.originY
            var bx1 = s.originX + s.cols * s.resolution
            var by1 = s.originY + s.rows * s.resolution
            if (maxC >= minC && maxR >= minR) {
                bx0 = s.originX + minC * s.resolution
                bx1 = s.originX + (maxC + 1) * s.resolution
                by0 = s.originY + minR * s.resolution
                by1 = s.originY + (maxR + 1) * s.resolution
            }
            for (l in s.locations) {
                bx0 = min(bx0, l.x); bx1 = max(bx1, l.x)
                by0 = min(by0, l.y); by1 = max(by1, l.y)
            }
            val margin = 0.5f
            val bounds = WorldBounds(bx0 - margin, by0 - margin, bx1 + margin, by1 + margin)

            // Confidence is measured on the final cells (including a manual invert).
            var confidence: Float? = null
            if (s.locations.isNotEmpty()) {
                var onFree = 0
                for (l in s.locations) {
                    val c = floor((l.x - s.originX) / s.resolution).toInt()
                    val r = floor((l.y - s.originY) / s.resolution).toInt()
                    var ok = false
                    loop@ for (dr in -2..2) for (dc in -2..2) {
                        val cc = c + dc
                        val rr = r + dr
                        if (cc < 0 || rr < 0 || cc >= s.cols || rr >= s.rows) continue
                        if (cells[rr * s.cols + cc] == Cell.FREE) { ok = true; break@loop }
                    }
                    if (ok) onFree++
                }
                confidence = onFree.toFloat() / s.locations.size
            }

            return MapGrid(s, cells, bestEnc, bestBottomUp, swap, confidence, bounds)
        }

        /** Largest factor so that neither side of a [cols] x [rows] grid exceeds [maxSide] cells. */
        fun downsampleFactor(cols: Int, rows: Int, maxSide: Int): Int =
            max(1, ceil(max(cols, rows) / maxSide.toFloat()).toInt())
    }
}
