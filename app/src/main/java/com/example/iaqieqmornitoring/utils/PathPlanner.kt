package com.example.iaqieqmornitoring.utils

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Predicts how temi will drive between two points of its saved map, so the app can warn BEFORE a
 * route starts if a leg is blocked or squeezes through a narrow gap.
 *
 * This is a prediction on the static map (walls, virtual walls) with a safety margin around every
 * obstacle. It is not temi's own planner: people, chairs and doors that changed since the map was
 * made are not known here. temi's sensors still decide at run time.
 */
object PlannerConfig {
    /** Half the width of temi, plus margin: the robot centre never goes closer than this to a wall. */
    const val ROBOT_RADIUS_M = 0.22f
    const val SAFETY_MARGIN_M = 0.10f
    const val INFLATION_M = ROBOT_RADIUS_M + SAFETY_MARGIN_M

    /** The path smoother only takes shortcuts that keep this much extra distance from walls. */
    const val SMOOTH_EXTRA_M = 0.08f

    /** A path that comes closer than this to a wall is reported as a narrow passage (a gap narrower than about 85 cm). */
    const val TIGHT_BELOW_M = 0.45f

    /** The planner prefers to stay this far beyond the inflation from walls (the middle of corridors). */
    const val COMFORT_EXTRA_M = 0.40f

    /** A start or goal inside the inflated zone is moved to the nearest free spot within this distance. */
    const val SNAP_RADIUS_M = 0.60f

    /** Used for the time estimate only. */
    const val SPEED_MPS = 0.5f

    /** The grid used for planning is at most this many cells wide / high (bigger maps are coarsened). */
    const val MAX_CELLS_PER_SIDE = 600
}

enum class LegStatus { CLEAR, TIGHT, UNKNOWN, BLOCKED }

/** One drive from [from] to [to]. [path] is the predicted polyline in map metres (empty if none). */
data class LegPlan(
    val from: String,
    val to: String,
    val a: Pt?,
    val b: Pt?,
    val path: List<Pt>,
    val distanceM: Float,
    /** Closest the predicted path comes to a wall, in metres (INFINITY if there are no walls near). */
    val minClearanceM: Float,
    val status: LegStatus,
    val note: String
) {
    val etaS: Int get() = (distanceM / PlannerConfig.SPEED_MPS).roundToInt()
}

class RouteStop(val name: String, val pt: Pt?)

/** The map reduced to a grid temi's centre can or cannot enter. */
class PlanGrid(
    val cols: Int,
    val rows: Int,
    val cellM: Float,
    val originX: Float,
    val originY: Float,
    /** Metres from the middle of each cell to the nearest wall / unknown cell / virtual wall. */
    val dist: FloatArray,
    val blocked: BooleanArray,
    val smoothBlocked: BooleanArray,
    val comfortM: Float
) {
    fun colOf(x: Float): Int = ((x - originX) / cellM).toInt().coerceIn(0, cols - 1)
    fun rowOf(y: Float): Int = ((y - originY) / cellM).toInt().coerceIn(0, rows - 1)
    fun xOf(col: Int): Float = originX + (col + 0.5f) * cellM
    fun yOf(row: Int): Float = originY + (row + 0.5f) * cellM
}

object PathPlanner {

    private const val SQ2 = 1.4142135f

    // ------------------------------------------------------------------------------------------
    // building the planning grid
    // ------------------------------------------------------------------------------------------

    fun buildGrid(grid: MapGrid): PlanGrid {
        val s = grid.snapshot
        val f = MapGrid.downsampleFactor(s.cols, s.rows, PlannerConfig.MAX_CELLS_PER_SIDE)
        val pc = (s.cols + f - 1) / f
        val pr = (s.rows + f - 1) / f
        val cellM = s.resolution * f

        // A planning cell is an obstacle if any of its map cells is a wall or unknown.
        val occ = BooleanArray(pc * pr)
        for (r in 0 until s.rows) {
            val prow = r / f
            for (c in 0 until s.cols) {
                if (grid.cells[r * s.cols + c] != Cell.FREE) occ[prow * pc + c / f] = true
            }
        }

        // Virtual walls are obstacles too.
        for (wall in s.virtualWalls) {
            val pts = wall.points
            val radius = ceil((wall.thickness / 2f) / cellM).toInt().coerceAtLeast(0)
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val len = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                val steps = max(1, ceil(len / (cellM * 0.5f)).toInt())
                for (k in 0..steps) {
                    val t = k.toFloat() / steps
                    val x = a.x + (b.x - a.x) * t
                    val y = a.y + (b.y - a.y) * t
                    val cc = ((x - s.originX) / cellM).toInt()
                    val rr = ((y - s.originY) / cellM).toInt()
                    for (dr in -radius..radius) {
                        for (dc in -radius..radius) {
                            val c2 = cc + dc
                            val r2 = rr + dr
                            if (c2 in 0 until pc && r2 in 0 until pr) occ[r2 * pc + c2] = true
                        }
                    }
                }
            }
        }

        val dist = distanceTransform(occ, pc, pr, cellM)
        val infl = PlannerConfig.INFLATION_M
        val blocked = BooleanArray(pc * pr) { dist[it] < infl }
        val smooth = BooleanArray(pc * pr) { dist[it] < infl + PlannerConfig.SMOOTH_EXTRA_M }
        return PlanGrid(pc, pr, cellM, s.originX, s.originY, dist, blocked, smooth, infl + PlannerConfig.COMFORT_EXTRA_M)
    }

    /** Two-pass chamfer distance (in metres) to the nearest obstacle cell. */
    private fun distanceTransform(occ: BooleanArray, cols: Int, rows: Int, cellM: Float): FloatArray {
        val inf = Float.POSITIVE_INFINITY
        val d = FloatArray(cols * rows) { if (occ[it]) 0f else inf }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                var v = d[i]
                if (r > 0) {
                    v = min(v, d[i - cols] + 1f)
                    if (c > 0) v = min(v, d[i - cols - 1] + SQ2)
                    if (c < cols - 1) v = min(v, d[i - cols + 1] + SQ2)
                }
                if (c > 0) v = min(v, d[i - 1] + 1f)
                d[i] = v
            }
        }
        for (r in rows - 1 downTo 0) {
            for (c in cols - 1 downTo 0) {
                val i = r * cols + c
                var v = d[i]
                if (r < rows - 1) {
                    v = min(v, d[i + cols] + 1f)
                    if (c < cols - 1) v = min(v, d[i + cols + 1] + SQ2)
                    if (c > 0) v = min(v, d[i + cols - 1] + SQ2)
                }
                if (c < cols - 1) v = min(v, d[i + 1] + 1f)
                d[i] = v
            }
        }
        for (i in d.indices) d[i] = d[i] * cellM
        return d
    }

    // ------------------------------------------------------------------------------------------
    // planning
    // ------------------------------------------------------------------------------------------

    /** Plans every leg between consecutive stops. A stop without a position gives an UNKNOWN leg. */
    fun planRoute(g: PlanGrid, stops: List<RouteStop>): List<LegPlan> {
        val legs = ArrayList<LegPlan>()
        for (i in 0 until stops.size - 1) legs += planLeg(g, stops[i], stops[i + 1])
        return legs
    }

    fun planLeg(g: PlanGrid, from: RouteStop, to: RouteStop): LegPlan {
        val a = from.pt
        val b = to.pt
        if (a == null || b == null) {
            val missing = if (a == null) from.name else to.name
            return LegPlan(from.name, to.name, a, b, emptyList(), 0f, Float.POSITIVE_INFINITY, LegStatus.UNKNOWN,
                "\"$missing\" is not on the loaded map")
        }

        val notes = ArrayList<String>()
        val s = snap(g, g.colOf(a.x), g.rowOf(a.y))
        val t = snap(g, g.colOf(b.x), g.rowOf(b.y))
        if (s == null) {
            return LegPlan(from.name, to.name, a, b, emptyList(), 0f, 0f, LegStatus.BLOCKED,
                "${from.name} is inside a wall or too close to one for temi to leave")
        }
        if (t == null) {
            return LegPlan(from.name, to.name, a, b, emptyList(), 0f, 0f, LegStatus.BLOCKED,
                "${to.name} is inside a wall or too close to one for temi to reach")
        }
        if (s.second) notes += "${from.name} is very close to an obstacle"
        if (t.second) notes += "${to.name} is very close to an obstacle"

        val cells = findPath(g, s.first, t.first)
            ?: return LegPlan(from.name, to.name, a, b, emptyList(), 0f, 0f, LegStatus.BLOCKED,
                "No free path from ${from.name} to ${to.name} (closed door, virtual wall or gap too narrow)")

        val sm = smooth(g, cells)
        val pts = ArrayList<Pt>(sm.size)
        for ((i, idx) in sm.withIndex()) {
            val c = idx % g.cols
            val r = idx / g.cols
            pts += when (i) {
                0 -> a
                sm.size - 1 -> b
                else -> Pt(g.xOf(c), g.yOf(r))
            }
        }
        if (pts.size == 1) pts += b

        var length = 0f
        for (i in 0 until pts.size - 1) {
            val p = pts[i]
            val q = pts[i + 1]
            length += sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y))
        }
        // Clearance is read from the cells of the raw A* path (before smoothing), ignoring the two end cells.
        var clearance = Float.POSITIVE_INFINITY
        for (k in 1 until cells.size - 1) clearance = min(clearance, g.dist[cells[k]])

        val tight = clearance < PlannerConfig.TIGHT_BELOW_M
        if (tight) {
            notes += "narrow passage (%.0f cm from a wall)".format(clearance * 100f)
        }
        return LegPlan(
            from.name, to.name, a, b, pts, length, clearance,
            if (tight) LegStatus.TIGHT else LegStatus.CLEAR,
            notes.joinToString("; ")
        )
    }

    /**
     * Returns the nearest cell temi's centre may enter (and whether the cell had to be moved),
     * or null if there is none within [PlannerConfig.SNAP_RADIUS_M].
     */
    private fun snap(g: PlanGrid, col: Int, row: Int): Pair<Int, Boolean>? {
        val idx = row * g.cols + col
        if (!g.blocked[idx]) return Pair(idx, false)
        val rad = ceil(PlannerConfig.SNAP_RADIUS_M / g.cellM).toInt().coerceAtLeast(1)
        var best = -1
        var bestD = Int.MAX_VALUE
        for (dr in -rad..rad) {
            for (dc in -rad..rad) {
                val c = col + dc
                val r = row + dr
                if (c < 0 || r < 0 || c >= g.cols || r >= g.rows) continue
                val i = r * g.cols + c
                if (g.blocked[i]) continue
                val d2 = dc * dc + dr * dr
                if (d2 < bestD) {
                    bestD = d2; best = i
                }
            }
        }
        return if (best < 0) null else Pair(best, true)
    }

    private class Node(val idx: Int, val f: Float)

    private fun heuristic(c: Int, r: Int, tc: Int, tr: Int): Float {
        val dx = abs(c - tc).toFloat()
        val dy = abs(r - tr).toFloat()
        return (dx + dy) + (SQ2 - 2f) * min(dx, dy)
    }

    /** A* over 8 neighbours; returns cell indices from start to goal, or null if the goal cannot be reached. */
    private fun findPath(g: PlanGrid, start: Int, goal: Int): IntArray? {
        val n = g.cols * g.rows
        val cost = FloatArray(n) { Float.MAX_VALUE }
        val came = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val open = PriorityQueue<Node>(256, Comparator { x, y -> x.f.compareTo(y.f) })
        val tc = goal % g.cols
        val tr = goal / g.cols

        cost[start] = 0f
        open.add(Node(start, heuristic(start % g.cols, start / g.cols, tc, tr)))

        while (true) {
            val cur = open.poll() ?: return null
            if (closed[cur.idx]) continue
            closed[cur.idx] = true
            if (cur.idx == goal) break

            val cx = cur.idx % g.cols
            val cy = cur.idx / g.cols
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= g.cols || ny >= g.rows) continue
                    val ni = ny * g.cols + nx
                    if (g.blocked[ni] || closed[ni]) continue
                    // no cutting corners between two blocked cells
                    if (dx != 0 && dy != 0 && (g.blocked[cy * g.cols + nx] || g.blocked[ny * g.cols + cx])) continue
                    val step = if (dx != 0 && dy != 0) SQ2 else 1f
                    val d = g.dist[ni]
                    val penalty = if (d < g.comfortM) (g.comfortM - d) / g.comfortM * 2f else 0f
                    val ng = cost[cur.idx] + step * (1f + penalty)
                    if (ng < cost[ni]) {
                        cost[ni] = ng
                        came[ni] = cur.idx
                        open.add(Node(ni, ng + heuristic(nx, ny, tc, tr)))
                    }
                }
            }
        }

        val out = ArrayList<Int>()
        var i = goal
        while (i != -1) {
            out.add(i)
            i = came[i]
        }
        out.reverse()
        return out.toIntArray()
    }

    /** Straight-line shortcuts that keep clear of walls ("string pulling"), so the line looks like a drive. */
    private fun smooth(g: PlanGrid, path: IntArray): IntArray {
        if (path.size <= 2) return path
        val out = ArrayList<Int>()
        out.add(path[0])
        var i = 0
        while (i < path.size - 1) {
            var j = path.size - 1
            while (j > i + 1 && !lineFree(g, path[i], path[j])) j--
            out.add(path[j])
            i = j
        }
        return out.toIntArray()
    }

    /** Bresenham line between two cells; the end cells themselves are not checked (they may sit near a wall). */
    private fun lineFree(g: PlanGrid, a: Int, b: Int): Boolean {
        var x0 = a % g.cols
        var y0 = a / g.cols
        val x1 = b % g.cols
        val y1 = b / g.cols
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            val here = y0 * g.cols + x0
            if (here != a && here != b && g.smoothBlocked[here]) return false
            if (x0 == x1 && y0 == y1) return true
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy; x0 += sx
            }
            if (e2 < dx) {
                err += dx; y0 += sy
            }
        }
    }
}
