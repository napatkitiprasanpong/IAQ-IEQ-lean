package com.example.iaqieqmornitoring.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.iaqieqmornitoring.utils.Cell
import com.example.iaqieqmornitoring.utils.LegPlan
import com.example.iaqieqmornitoring.utils.LegStatus
import com.example.iaqieqmornitoring.utils.MapGrid
import com.example.iaqieqmornitoring.utils.Pt
import com.example.iaqieqmornitoring.utils.RobotPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val ColUnknown = 0xFF070D1A.toInt()
private val ColFree = 0xFF1B2A47.toInt()
private val ColWall = 0xFFCBD5E1.toInt()

val ColorLegClear = Color(0xFF22C1DC)
val ColorLegTight = Color(0xFFFFC107)
val ColorLegBlocked = Color(0xFFFF5252)
val ColorLegUnknown = Color(0xFF94A3B8)

fun legColor(s: LegStatus): Color = when (s) {
    LegStatus.CLEAR -> ColorLegClear
    LegStatus.TIGHT -> ColorLegTight
    LegStatus.BLOCKED -> ColorLegBlocked
    LegStatus.UNKNOWN -> ColorLegUnknown
}

/** The map drawn as a bitmap (north up, x right). Large maps are reduced to at most [maxSide] pixels. */
private class MapBitmap(val image: ImageBitmap, val factor: Int)

private fun renderBitmap(grid: MapGrid, maxSide: Int = 1600): MapBitmap {
    val s = grid.snapshot
    val f = MapGrid.downsampleFactor(s.cols, s.rows, maxSide)
    val w = (s.cols + f - 1) / f
    val h = (s.rows + f - 1) / f
    val px = IntArray(w * h) { ColUnknown }
    for (row in 0 until s.rows) {
        val y = h - 1 - row / f                      // bottom-up cells -> top-down image rows
        for (col in 0 until s.cols) {
            val v = grid.cells[row * s.cols + col]
            if (v == Cell.UNKNOWN) continue
            val i = y * w + col / f
            if (v == Cell.OCCUPIED) px[i] = ColWall
            else if (px[i] != ColWall) px[i] = ColFree
        }
    }
    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    return MapBitmap(bmp.asImageBitmap(), f)
}

/**
 * The robot's map: free space, walls, virtual walls, every saved location, and optionally the predicted
 * route ([legs]), the robot, and the path temi is following right now ([navPath]).
 * Drag to pan, pinch to zoom, tap a location to select it.
 */
@Composable
fun MapView(
    grid: MapGrid?,
    modifier: Modifier = Modifier,
    legs: List<LegPlan> = emptyList(),
    stops: List<String> = emptyList(),
    robot: RobotPose? = null,
    navPath: List<Pt> = emptyList(),
    selected: String? = null,
    onSelect: (String?) -> Unit = {},
    emptyText: String = "No map"
) {
    if (grid == null) {
        Box(modifier.background(Color(0xFF070D1A), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(emptyText, color = ColorMuted, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
        }
        return
    }

    val bitmap by produceState<MapBitmap?>(null, grid) {
        value = withContext(Dispatchers.Default) { renderBitmap(grid) }
    }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(20f) }       // pixels per metre
    var cx by remember { mutableFloatStateOf(0f) }           // world point at the centre of the view
    var cy by remember { mutableFloatStateOf(0f) }

    fun fit() {
        if (viewSize.width == 0 || viewSize.height == 0) return
        val b = grid.bounds
        var minX = b.minX; var maxX = b.maxX; var minY = b.minY; var maxY = b.maxY
        for (leg in legs) for (p in leg.path) {
            minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val w = max(maxX - minX, 1f)
        val h = max(maxY - minY, 1f)
        scale = min(viewSize.width / w, viewSize.height / h) * 0.95f
        cx = (minX + maxX) / 2f
        cy = (minY + maxY) / 2f
    }

    LaunchedEffect(viewSize, grid.snapshot.mapId, grid.snapshot.cols) { fit() }

    fun sx(x: Float) = viewSize.width / 2f + (x - cx) * scale
    fun sy(y: Float) = viewSize.height / 2f - (y - cy) * scale

    val snap = grid.snapshot
    val inRoute = stops.map { it.lowercase() }

    Box(
        modifier
            .background(Color(0xFF070D1A), RoundedCornerShape(16.dp))
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(2f, 600f)
                    // keep the world point under the fingers where it is
                    val wx = cx + (centroid.x - viewSize.width / 2f) / oldScale
                    val wy = cy - (centroid.y - viewSize.height / 2f) / oldScale
                    scale = newScale
                    cx = wx - (centroid.x - viewSize.width / 2f) / newScale - pan.x / newScale
                    cy = wy + (centroid.y - viewSize.height / 2f) / newScale + pan.y / newScale
                }
            }
            .pointerInput(grid) {
                detectTapGestures { tap ->
                    var best: String? = null
                    var bestD = 48f * 48f
                    for (l in snap.locations) {
                        val dx = sx(l.x) - tap.x
                        val dy = sy(l.y) - tap.y
                        val d = dx * dx + dy * dy
                        if (d < bestD) { bestD = d; best = l.name }
                    }
                    onSelect(best)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // map image
            bitmap?.let { mb ->
                val w = mb.image.width
                val h = mb.image.height
                val wm = w * mb.factor * snap.resolution
                val hm = h * mb.factor * snap.resolution
                drawImage(
                    image = mb.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(w, h),
                    dstOffset = IntOffset(sx(snap.originX).roundToInt(), sy(snap.originY + hm).roundToInt()),
                    dstSize = IntSize((wm * scale).roundToInt().coerceAtLeast(1), (hm * scale).roundToInt().coerceAtLeast(1))
                )
            }

            // navigation paths and virtual walls
            for (path in snap.greenPaths) {
                for (i in 0 until path.points.size - 1) {
                    drawLine(Color(0x6600FFA3), Offset(sx(path.points[i].x), sy(path.points[i].y)),
                        Offset(sx(path.points[i + 1].x), sy(path.points[i + 1].y)), 2f, StrokeCap.Round)
                }
            }
            for (wall in snap.virtualWalls) {
                val thick = max(3f, wall.thickness * scale)
                for (i in 0 until wall.points.size - 1) {
                    drawLine(Color(0xFFFF5252), Offset(sx(wall.points[i].x), sy(wall.points[i].y)),
                        Offset(sx(wall.points[i + 1].x), sy(wall.points[i + 1].y)), thick, StrokeCap.Round)
                }
            }

            // predicted route
            for (leg in legs) {
                val color = legColor(leg.status)
                val dashed = leg.status == LegStatus.BLOCKED || leg.status == LegStatus.UNKNOWN
                val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null
                val pts = if (leg.path.isNotEmpty()) leg.path else listOfNotNull(leg.a, leg.b)
                for (i in 0 until pts.size - 1) {
                    drawLine(color, Offset(sx(pts[i].x), sy(pts[i].y)), Offset(sx(pts[i + 1].x), sy(pts[i + 1].y)),
                        5f, StrokeCap.Round, effect)
                }
            }

            // the path temi itself is following
            if (navPath.size > 1) {
                val effect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (i in 0 until navPath.size - 1) {
                    drawLine(Color(0xFFFF9800), Offset(sx(navPath[i].x), sy(navPath[i].y)),
                        Offset(sx(navPath[i + 1].x), sy(navPath[i + 1].y)), 3f, StrokeCap.Round, effect)
                }
            }

            // locations
            for (l in snap.locations) {
                val route = l.name.lowercase() in inRoute
                val isSel = selected != null && l.name.equals(selected, ignoreCase = true)
                val c = Offset(sx(l.x), sy(l.y))
                if (isSel) drawCircle(Color.White, 15f, c, style = Stroke(3f))
                drawCircle(if (route) ColorLegClear else Color(0xFF64748B), if (route) 8f else 6f, c)
            }

            // robot
            if (robot != null) {
                val c = Offset(sx(robot.x), sy(robot.y))
                drawCircle(Color(0x5500FFA3), 16f, c)
                drawCircle(Color(0xFF00FFA3), 8f, c)
                drawLine(Color(0xFF00FFA3), c, Offset(c.x + cos(robot.yaw) * 22f, c.y - sin(robot.yaw) * 22f), 4f, StrokeCap.Round)
            }
        }

        // location labels (numbered when they are stops of the previewed route)
        for (l in snap.locations) {
            val order = stops.indexOfFirst { it.equals(l.name, ignoreCase = true) }
            val label = if (order >= 0) "${order + 1} · ${l.name}" else l.name
            Text(
                label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = if (l.name.equals(selected, true)) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .offset { IntOffset(sx(l.x).roundToInt() + 10, sy(l.y).roundToInt() - 22) }
                    .background(Color(0xCC020617), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // zoom controls
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapButton("+") { scale = (scale * 1.4f).coerceAtMost(600f) }
            MapButton("−") { scale = (scale / 1.4f).coerceAtLeast(2f) }
            MapButton("Fit") { fit() }
        }
    }
}

@Composable
private fun MapButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(Color(0xDD1E293B), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
