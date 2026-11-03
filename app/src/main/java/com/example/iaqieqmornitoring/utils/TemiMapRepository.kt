package com.example.iaqieqmornitoring.utils

import com.robotemi.sdk.Robot
import com.robotemi.sdk.map.LayerPose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

data class MapUiState(
    /** Where the map comes from, shown on the Map tab. */
    val source: String = "",
    /** Maps backed up on the temi cloud (or the simulated maps). */
    val maps: List<CloudMap> = emptyList(),
    val currentMapId: String = "",
    val grid: MapGrid? = null,
    val loading: Boolean = false,
    val message: String = "",
    val error: String = "",
    val robot: RobotPose? = null,
    /** The path temi itself is following right now (live, while it drives). */
    val navPath: List<Pt> = emptyList(),
    /** temi refused to give the map: the app probably lacks the "Map" permission. */
    val needsPermission: Boolean = false
) {
    val snapshot: MapSnapshot? get() = grid?.snapshot
}

/**
 * Reads the map of the robot: the occupancy image, every saved location, virtual walls, and the list
 * of maps backed up on the temi cloud, and can switch the robot to another of those maps.
 *
 * The "temi cloud" part is done by the robot itself: `getMapList()` returns the maps the robot has
 * backed up to the temi cloud, and `loadMap(id)` makes the robot fetch one (or use its local cache).
 * The app needs no cloud login of its own, but the robot must be signed in and online, and the app
 * needs the temi "Map" permission (declared in AndroidManifest.xml).
 *
 * With the simulated robot, [SimMaps] stands in for all of it.
 */
object TemiMapRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /** Status numbers from temi's OnLoadMapStatusChangedListener (0 = complete, >= 1000 = error). */
    private val loadStatus = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    @Volatile private var listenersAttached = false
    private var simIndex = 0
    private var lastSnapshot: MapSnapshot? = null

    private fun robotOrNull(): Robot? = try {
        Robot.getInstance()
    } catch (e: Throwable) {
        null
    }

    private fun busy(): Boolean = MissionManager.state.value.isActive || RoutineManager.state.value.isActive

    // ------------------------------------------------------------------------------------------
    // modes
    // ------------------------------------------------------------------------------------------

    /** Called when the "Simulated robot" setting is on. */
    fun useSimulation() {
        simIndex = 0
        val snap = SimMaps.snapshot(simIndex)
        val home = snap.location(AppSettings.homeBase.value) ?: snap.locations.firstOrNull()
        lastSnapshot = snap
        _state.value = MapUiState(
            source = "Simulated robot (sample maps, no temi cloud)",
            maps = SimMaps.cloudMaps(),
            currentMapId = SimMaps.id(simIndex),
            grid = MapGrid.build(snap, AppSettings.mapOrientation.value, AppSettings.mapInvert.value),
            robot = home?.let { RobotPose(it.x, it.y, 0f) },
            message = "Simulated map"
        )
        TemiRepository.setLocations(snap.locations.map { it.name })
    }

    /** Called when the real robot is used. The map itself is read once temi reports it is ready. */
    fun useReal() {
        lastSnapshot = null
        _state.value = MapUiState(source = "temi robot · maps from the temi cloud", message = "Waiting for temi…")
    }

    fun onRobotReady() {
        attachListeners()
        refresh()
    }

    // ------------------------------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------------------------------

    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** The map the robot is using now, loading it first if it has not been read yet. */
    suspend fun ensureMap(): MapGrid? {
        _state.value.grid?.let { return it }
        refreshNow()
        return _state.value.grid
    }

    /** The robot's position on the map right now (null if unknown). */
    suspend fun currentPose(): RobotPose? {
        withContext(Dispatchers.IO) { pollPositionNow() }
        return _state.value.robot
    }

    fun pollPosition() {
        scope.launch { pollPositionNow() }
    }

    private fun pollPositionNow() {
        if (TemiRepository.state.value.simulated) return
        val robot = robotOrNull() ?: return
        if (!TemiRepository.state.value.ready) return
        try {
            val p = robot.getPosition() ?: return
            _state.update { it.copy(robot = RobotPose(p.x, p.y, p.yaw)) }
        } catch (e: Throwable) {
            // keep the last known position
        }
    }

    /** The simulated robot "drives" instantly: it is at the location it last arrived at. */
    fun simArrived(location: String) {
        val snap = lastSnapshot ?: return
        val l = snap.location(location) ?: return
        _state.update { it.copy(robot = RobotPose(l.x, l.y, l.theta), navPath = emptyList()) }
    }

    fun clearNavPath() {
        if (_state.value.navPath.isNotEmpty()) _state.update { it.copy(navPath = emptyList()) }
    }

    /** Rebuilds the free/wall cells after the display overrides on the Map tab changed. */
    fun rebuildGrid() {
        val snap = lastSnapshot ?: return
        scope.launch {
            val g = MapGrid.build(snap, AppSettings.mapOrientation.value, AppSettings.mapInvert.value)
            _state.update { it.copy(grid = g) }
        }
    }

    private suspend fun refreshNow() {
        lock.withLock { doRefresh() }
    }

    private suspend fun doRefresh() {
        if (TemiRepository.state.value.simulated) return
        _state.update { it.copy(loading = true, error = "", needsPermission = false, message = "Reading the map from temi…") }
        try {
            val robot = robotOrNull()
            if (robot == null || !TemiRepository.state.value.ready) {
                _state.update {
                    it.copy(loading = false, error = "temi is not connected. The map is read from the robot, which syncs with the temi cloud.")
                }
                return
            }
            val maps = withContext(Dispatchers.IO) { readMapList(robot) }
            val snap = withContext(Dispatchers.IO) { readSnapshot(robot) }
            if (snap == null) {
                _state.update {
                    it.copy(
                        loading = false, maps = maps, needsPermission = true,
                        error = "temi did not return a map. Is a map saved on the robot, and is this app allowed to read maps (Map permission)?"
                    )
                }
                return
            }
            lastSnapshot = snap
            val grid = withContext(Dispatchers.IO) {
                MapGrid.build(snap, AppSettings.mapOrientation.value, AppSettings.mapInvert.value)
            }
            val currentId = maps.firstOrNull { it.name == snap.name }?.id ?: _state.value.currentMapId
            _state.update {
                it.copy(
                    loading = false, maps = maps, currentMapId = currentId, grid = grid,
                    message = "Map \"${snap.name}\" · ${snap.locations.size} locations"
                )
            }
            pollPositionNow()
        } catch (e: Throwable) {
            val denied = e is SecurityException || (e.message ?: "").contains("permission", ignoreCase = true)
            _state.update {
                it.copy(
                    loading = false, needsPermission = denied,
                    error = "Could not read the map: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun readMapList(robot: Robot): List<CloudMap> {
        return try {
            robot.getMapList().orEmpty().map { CloudMap(it.id ?: "", it.name ?: "") }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Converts temi's MapDataModel into our own [MapSnapshot]. Null if temi has no map to give. */
    private fun readSnapshot(robot: Robot): MapSnapshot? {
        val md = robot.getMapData() ?: return null
        val img = md.mapImage ?: return null
        val rows = img.rows
        val cols = img.cols
        val data = img.data ?: return null
        if (rows <= 0 || cols <= 0 || data.size < rows * cols) return null

        val raw = ShortArray(rows * cols)
        for (i in raw.indices) raw[i] = data[i].coerceIn(-32768, 32767).toShort()

        val info = md.mapInfo
        val resolution = (info?.resolution ?: 0f).let { if (it > 0f) it else 0.05f }

        val locations = ArrayList<MapLocation>()
        for (layer in md.locations.orEmpty()) {
            val pose = layer.layerPoses?.firstOrNull() ?: continue
            locations += MapLocation(layer.layerId ?: "", pose.x, pose.y, pose.theta)
        }
        val walls = md.virtualWalls.orEmpty().map { layer ->
            MapPolyline(
                layer.layerId ?: "wall",
                layer.layerPoses.orEmpty().map { Pt(it.x, it.y) },
                layer.layerThickness
            )
        }
        val paths = md.greenPaths.orEmpty().map { layer ->
            MapPolyline(
                layer.layerId ?: "path",
                layer.layerPoses.orEmpty().map { Pt(it.x, it.y) },
                layer.layerThickness
            )
        }
        return MapSnapshot(
            mapId = md.mapId ?: "",
            name = md.mapName?.takeIf { it.isNotBlank() } ?: "Current map",
            cols = cols, rows = rows, resolution = resolution,
            originX = info?.originX ?: 0f, originY = info?.originY ?: 0f,
            raw = raw, locations = locations, virtualWalls = walls, greenPaths = paths
        )
    }

    // ------------------------------------------------------------------------------------------
    // switching maps
    // ------------------------------------------------------------------------------------------

    /** Makes temi load another map (from the temi cloud or its local cache). */
    fun switchMap(mapId: String) {
        if (busy()) {
            _state.update { it.copy(error = "Stop the running mission or routine before changing the map.") }
            return
        }
        scope.launch {
            _state.update { it.copy(loading = true, error = "", message = "Loading the map…") }

            if (TemiRepository.state.value.simulated) {
                simIndex = SimMaps.indexOf(mapId)
                val snap = SimMaps.snapshot(simIndex)
                lastSnapshot = snap
                val g = MapGrid.build(snap, AppSettings.mapOrientation.value, AppSettings.mapInvert.value)
                val home = snap.location(AppSettings.homeBase.value) ?: snap.locations.firstOrNull()
                _state.update {
                    it.copy(
                        loading = false, currentMapId = SimMaps.id(simIndex), grid = g, navPath = emptyList(),
                        robot = home?.let { h -> RobotPose(h.x, h.y, 0f) },
                        message = "Map \"${snap.name}\" · ${snap.locations.size} locations"
                    )
                }
                TemiRepository.setLocations(snap.locations.map { it.name })
                return@launch
            }

            val robot = robotOrNull()
            if (robot == null || !TemiRepository.state.value.ready) {
                _state.update { it.copy(loading = false, error = "temi is not connected.") }
                return@launch
            }

            val result: Int = coroutineScope {
                // Listen BEFORE sending the command so the answer cannot be missed.
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(120_000L) { loadStatus.first { it == 0 || it >= 1000 } }
                }
                try {
                    robot.loadMap(mapId)
                } catch (e: Throwable) {
                    waiter.cancel()
                    return@coroutineScope -1
                }
                waiter.await() ?: -2
            }

            when (result) {
                0 -> {
                    refreshNow()
                    TemiRepository.refresh()
                }
                -1 -> _state.update { it.copy(loading = false, error = "temi did not accept the load-map command.") }
                -2 -> _state.update { it.copy(loading = false, error = "Loading the map timed out. Check temi's internet connection.") }
                2001 -> _state.update { it.copy(loading = false, error = "temi must be on its charger to switch maps.") }
                2002 -> _state.update { it.copy(loading = false, error = "temi is busy with another task. Try again in a moment.") }
                3000 -> _state.update { it.copy(loading = false, error = "temi timed out while loading the map.") }
                else -> _state.update { it.copy(loading = false, error = "temi could not load the map (error $result).") }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // permission + live listeners
    // ------------------------------------------------------------------------------------------

    /**
     * Asks temi to let this app read maps. The SDK type is looked up by name so that an SDK version
     * without it cannot break the build. temi shows a prompt on its own screen.
     */
    fun requestMapPermission(): String {
        val robot = robotOrNull() ?: return "temi SDK is not available."
        return try {
            val permissionClass = Class.forName("com.robotemi.sdk.permission.Permission")
            val mapPermission = permissionClass.enumConstants?.firstOrNull { (it as Enum<*>).name.equals("MAP", ignoreCase = true) }
                ?: return "This temi SDK has no Map permission."
            val request = robot.javaClass.methods.firstOrNull { it.name == "requestPermissions" && it.parameterTypes.size == 2 }
                ?: return "This temi SDK cannot request permissions."
            request.invoke(robot, listOf(mapPermission), 1001)
            "Permission requested. Accept the prompt on temi's screen, then tap Refresh."
        } catch (e: Throwable) {
            "Could not request the permission: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun attachListeners() {
        if (listenersAttached) return
        val robot = robotOrNull() ?: return
        listenersAttached = true

        proxyListener(robot, "com.robotemi.sdk.map.OnLoadMapStatusChangedListener", "addOnLoadMapStatusChangedListener") { name, args ->
            if (name == "onLoadMapStatusChanged") {
                val status = args?.getOrNull(0) as? Int
                if (status != null) loadStatus.tryEmit(status)
            }
        }
        proxyListener(robot, "com.robotemi.sdk.listeners.OnGoToNavPathChangedListener", "addOnGoToNavPathChangedListener") { name, args ->
            if (name == "onGoToNavPathChanged") {
                val list = args?.getOrNull(0) as? List<*>
                val pts = list.orEmpty().mapNotNull { it as? LayerPose }.map { Pt(it.x, it.y) }
                _state.update { it.copy(navPath = pts) }
            }
        }
    }

    /**
     * Registers a listener for a temi SDK interface without implementing the interface in code
     * (a dynamic proxy), so a launcher / SDK that lacks it only means "no live info", never a crash.
     */
    private fun proxyListener(robot: Any, interfaceName: String, adderName: String, onCall: (String, Array<Any?>?) -> Unit) {
        try {
            val iface = Class.forName(interfaceName)
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "TemiMapListener"
                    else -> {
                        try {
                            onCall(method.name, args)
                        } catch (e: Throwable) {
                            // never let a listener crash the robot service
                        }
                        null
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
            robot.javaClass.getMethod(adderName, iface).invoke(robot, proxy)
        } catch (e: Throwable) {
            // not available on this launcher / SDK version
        }
    }
}
