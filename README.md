# IAQ-IEQ

An Android app (Kotlin, Jetpack Compose, Material 3) for indoor air quality (IAQ) and indoor environment quality (IEQ) that runs on a **modified temi robot** and turns it into a mobile indoor-environment inspector.

* A **Raspberry Pi** mounted on temi reads the environmental sensors and publishes the readings over **MQTT**.
* The app shows the readings live, converts them into an **Indoor Air Quality (IAQ)** score, and plots trends.
* The app drives temi along configurable **inspection routes** (ordered lists of temi's saved locations). At each stop it samples the air and records the readings, then saves the results as a CSV file.

```
 Sensors ──► Raspberry Pi ──MQTT──►  ┌──────────────────────────┐
 (temp, humidity, CO2, PM, ...)      │  This app (on temi's     │ ──temi SDK──► temi navigation
                                     │  Android tablet)         │               (go to saved locations)
                                     └──────────────────────────┘
```

## Features

|Area|What it does|
|-|-|
|**Dashboard**|Overall IAQ gauge and level, nine sensor cards, and a Sudden Mission quick-start panel.|
|**Analytics**|One live graph per sensor (temperature, humidity, CO₂, PM2.5, PM10, TVOC, HCHO, light, sound) with min / avg / max, IAQ breakpoint lines and a 1 min – 1 h window. The history is kept in memory for the last hour.|
|**Routine**|All runs in one tab: **Sudden mission** (start now, sample every stop, CSV), **Timed mission** (daily at a set time, asks a question at each stop) and **Custom routine** (daily, own sampling time per stop, uploaded to Google Sheets). **Every run shows the predicted route on the map before it starts.**|
|**Map**|The robot's map with all saved locations, its live position and virtual walls. Maps come from the temi cloud backup list and can be switched.|
|**Settings**|Robot and mission behaviour, route editor, Raspberry Pi connection, editable IAQ thresholds.|
|**Startup check**|Live status of the MQTT link, data freshness, sensors reporting, battery, saved locations and storage.|
|**IAQI scale**|Reference screen with the breakpoint tables and the current values.|

**Sensors (9):** temperature, humidity, CO₂, PM2.5, PM10, TVOC, HCHO, light, sound.
**IAQ levels:** Good, Moderate, Polluted, Very Polluted, Severe.

## Requirements

* temi robot (V2: Android 6, or V3: Android 11) with the temi launcher that supports the SDK
* Raspberry Pi on the same network as the temi tablet, with an MQTT broker (Mosquitto)
* Android Studio (recent version), JDK 17+, Android SDK 36
* App: `minSdk 23`, `targetSdk 36`
* Dependencies (added in `app/build.gradle.kts`): `com.robotemi:sdk:1.138.0`, `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`

## Setup

### 1. Save locations on temi

The app can only send temi to locations that already exist on its map. On the robot, map the area and save one location per inspection point (for example "Entrance", "Lab A"). The charging station is called **home base**.

### 2. Set up the Raspberry Pi

```bash
sudo apt install mosquitto mosquitto-clients
pip install paho-mqtt
echo -e "listener 1883\nallow_anonymous true" | sudo tee /etc/mosquitto/conf.d/iaq.conf
sudo systemctl restart mosquitto
```

Then run your sensor code so it publishes to the broker. `docs/pi_sensor_publisher_example.py` is a working example; replace its fake `read_*` functions with your real sensor readings.

> `allow_anonymous true` is fine on a private robot network. If the network is shared, configure a username and password in Mosquitto and enter them in the app's Settings.

**Message format.** Publish JSON to the topic (default `pi/01/telemetry/iaq`), about every 2 seconds:

```json
{"temp":24.1,"humidity":55,"co2":620,"pm25":9,"pm10":14,"tvoc":0.2,"hcho":0.02,"light":310,"sound":42}
```

The key names of `RaspberryPi.py` (`temp_c`, `rh_pct`, `co2_ppm`, `pm25_ugm3`, `pm10_ugm3`, `tvoc_ppb`, `lux`, `dba`) are recognised too; keys the app has no use for (`pm1_ugm3`, `nox_index`) are ignored. An older saved topic `iaq/sensors` is replaced by the new default the first time this version starts.

* Missing keys keep their previous value.
* Alternative key names are accepted (for example `temperature`, `rh`, `pm2_5`, `lux`, `noise`).
* The values can also sit inside a `"sensors"` or `"data"` object.
* One sensor per topic also works: set the topic to `pi/01/telemetry/iaq/#` and publish a plain number to `pi/01/telemetry/iaq/co2`, `pi/01/telemetry/iaq/temp`, and so on.

Units: °C, %, ppm, µg/m³ (PM), mg/m³ (TVOC, HCHO), lux, dB.

### 3. Build and install the app

> Application id: `com.leslie.iaqieqmornitoring` (unchanged from before the split, so existing installs, saved settings and the MatePet bridge keep working). The app label is now **IAQ-IEQ**.

1. Open the project in Android Studio and let Gradle sync.
2. Connect to temi with `adb connect <temi-ip>` (enable developer options on temi), or use the temi-recommended install method.
3. Run the `app` configuration. The app appears in temi's app list.

### 4. Configure the app

Open **Settings**:

1. **Raspberry Pi Sensors:** the Pi's address is pre-filled as `10.2.115.198` (change it here if the Pi gets a different address), then check the port (1883) and topic and tap **Save & Reconnect**. The default lives in `DEFAULT_PI_HOST` in `utils/AppSettings.kt`; an older saved value of `raspberrypi.local` is replaced by it.
2. **Routes & Locations:** choose a route, then add temi's saved locations in the order they should be visited.
3. **temi Robot & Mission:** set the sampling time per stop (sudden mission), the navigation timeout, retries, home base name, whether to return home at the end, and **Start this app automatically when temi starts** (see below).

#### Starting the app automatically

Switch on **Start this app automatically when temi starts** (Settings → temi Robot & Mission). It does two things:

* It registers the app as temi's **start-up (kiosk) app** through the temi SDK (`requestToBeKioskApp`). temi may show a confirmation on its screen; accept it. Under the switch the app shows whether temi's launcher has this app selected. This is the route that also works on newer Android versions.
* It makes the app open itself when the tablet finishes booting (a boot receiver). On Android 10 and newer, Android blocks apps from opening screens in the background, so this part may do nothing there. The tablet in this project runs Android 6, where it works.

Turn the switch off to stop the boot receiver. If temi's launcher keeps opening the app, change the start-up app in temi's own settings. Test it once by restarting temi.

Check the **Startup** screen: every card should be green.

## Routine tab

One tab for every kind of run. Pick the kind at the top:

|Kind|Starts|What temi does|
|-|-|-|
|**Sudden mission**|now, when you press **Preview & start**|Drives the chosen route; at each stop waits the sampling time (Settings), records the readings and saves a CSV. Optionally returns to home base.|
|**Timed mission**|every day at a set time (or **Preview & run** now)|Drives the route; at each stop speaks a message and shows a popup (Yes / Come back in N mins). "Come back" creates a temp mission with a timer. Always returns to home base. (This was the old *Routine*.)|
|**Custom routine**|every day at a set time (or **Preview & run** now)|Drives the route and, at each stop, collects sensor data for a duration and interval you choose per stop, then uploads everything to Google Sheets, see [`docs/IAQ_IEQ_ROUTINE.md`](docs/IAQ_IEQ_ROUTINE.md). (This was the old *IAQ-IEQ routine*.)|

### Route preview (before every run)

Pressing start never starts the run directly. A preview opens first: the map with the **predicted drive** from temi's current position through every stop and back to home base, leg by leg, with distance, a time estimate and a verdict:

* **Clear**: a free path exists with the safety margin.
* **Narrow**: the path squeezes through a gap narrower than about 85 cm.
* **Blocked**: no free path (closed door, virtual wall, gap under about 64 cm). The button changes to **Start anyway**.
* **Unknown**: a stop is not on the loaded map, or the map could not be read (see the Map tab).

The prediction is made by the app on temi's saved map (A* on the occupancy grid, walls and virtual walls inflated by a 32 cm safety margin, then straightened). It is **not temi's own planner**, and people or objects that are not on the map are invisible to it; temi's sensors still stop the robot. The margins are constants in `utils/PathPlanner.kt` (`PlannerConfig`): tune them to your robot.

Voice (MatePet) starts show the same preview on temi's screen and wait for "yes" / "no". Timed missions and custom routines that start by themselves have nobody to look at the preview: the app predicts the route first and **does not start if a leg is blocked** (logged in History). Turn that off in the Routine settings card.

### Timed mission details

1. At the set time, every day, temi starts the route. (The app must be open.)
2. At each stop temi **speaks the message** and a **popup** appears: **Yes** (carry on) or **Come back in N mins** (carry on, and a **temp mission** is created for that stop).
3. After the last stop temi **returns to home base**; every temp mission's N-minute timer starts.
4. When a timer ends temi drives to that stop and asks again. Yes clears it; Come back replaces it with a new temp mission.
5. When no temp missions are left, the run is done.

`N` is 5 by default (1 to 60 min). **Popup waits for an answer** (10 to 300 s): no answer counts as "Come back in N mins". Limits: routines only run while the app is open; waiting temp missions are lost if the app is closed; a run starts only within 10 minutes of its time, at most once per day, and not while another run is active.

### Results file (sudden mission)

Each sudden mission writes a CSV to the app's external files folder under `missions/` (`mission_20261021_101500_Route_A.csv`). Columns:

`time, route, stop, location, outcome, iaq, level, temp_c, humidity_pct, co2_ppm, pm25, pm10, tvoc, hcho, light_lux, sound_db, data_fresh, note`

`data_fresh` is `false` if no sensor message arrived in the 15 seconds before the reading, so stale data can be told apart.

## Map tab

Shows the map of the robot (free space, walls, virtual walls), every saved location (tap to highlight; routes using it are listed), temi's live position and, while it drives, the path temi itself is following (orange dashes). Drag to pan, pinch or +/− to zoom.

**temi cloud.** The map list is `getMapList()`: the maps the robot has backed up to the temi cloud. Choosing another map calls `loadMap(id)`; temi downloads it (or uses its cache). The app needs no cloud login of its own, but the robot must be signed in and online, must be on its charger to switch maps, and the app needs the temi **Map** permission (declared in the manifest; if temi refuses, use **Allow map access**). Only the map the robot has loaded can be drawn, because the SDK gives map data for the loaded map only. Switching maps changes the saved locations, so check your routes.

**Map display.** The app works out how temi's occupancy numbers are laid out by checking which reading puts temi's saved locations on free floor. If the map looks upside down or inverted, override *Row order* / *Free space / walls* in the Map display card.

With **Simulated robot** on, two sample maps are used (the second one has a closed room and a narrow door, to see the Blocked and Narrow warnings).

## Analytics tab

One live line graph per sensor. Choose 1 min, 5 min, 15 min or 1 hour; the dashed lines are that sensor's IAQ breakpoints; tap a graph for the detail screen. History is kept in memory for the last hour (empty after the app restarts); **Clear** empties it.

## IAQ score

Each sensor has four breakpoints that define its five quality bands. Each reading is converted to a 0–100 score, and the **overall IAQ score is the average of the nine scores**. The breakpoints can be edited in **Settings → IAQ Thresholds** (with a reset to defaults).

Default breakpoints:

|Sensor|B1|B2|B3|B4|
|-|-|-|-|-|
|Temperature (°C)|18|25|30|35|
|Humidity (%)|30|50|70|85|
|CO₂ (ppm)|400|600|1000|2500|
|PM2.5 (µg/m³)|12|35|55|150|
|PM10 (µg/m³)|20|50|100|200|
|TVOC (mg/m³)|0.3|0.5|1.0|3.0|
|HCHO (mg/m³)|0.03|0.05|0.10|0.20|
|Light (lux)|100|300|500|1000|
|Sound (dB)|30|50|70|90|

## Testing without hardware

In **Settings** you can switch on:

* **Simulated robot:** a fake temi with sample locations (Entrance, Lab A, Lab B, Corridor, home base). Missions run end to end.
* **Simulated sensors:** random values every 2 seconds, with no Pi needed.

The startup screen shows simulated sources in amber so they cannot be mistaken for real ones. Switch both off for real use.

## Related apps

This project used to be one app. It was split into two separate apps, each with its own repository:

|App|Repo folder|What it does|
|-|-|-|
|**IAQ-IEQ**|`IAQ-IEQ`|Air-quality and comfort monitoring: dashboard, missions, routines, Google Sheets upload, voice bridge.|
|**temi-of-thinh (TOT)**|`temi-of-thinh`|A test and utility app for temi: camera and depth view, gesture control, and ASL hand reading.|

The apps do not depend on each other and can be installed side by side on the same temi.

## Voice / AI control (MatePet)

The app exposes a small signature-protected bridge so the MatePet voice app can read live air data and start or stop missions and routines by voice. See [`docs/AI_BRIDGE.md`](docs/AI_BRIDGE.md).

## Project structure

```
app/src/main/java/com/example/iaqieqmornitoring/
├── MainActivity.kt            starts settings, temi and sensor links
├── navigation/                screen routes
├── screens/                   Startup, Dashboard, Analytics, Detail, IAQI scale, Routine (all run kinds), Map, Settings
├── bridge/                    AI/voice bridge (commands in, status out)
├── components/                gauges, cards, top bar, sidebar, shared form widgets, RoutinePromptHost (the routine popup)
└── utils/
    ├── AppSettings.kt         persisted settings and routes
    ├── ThresholdStore.kt      editable IAQ breakpoints
    ├── SensorRepository.kt    current readings, link state, simulator
    ├── PiSensorClient.kt      MQTT client and payload parser
    ├── TemiRepository.kt      the only class that uses the temi SDK (plus simulated robot)
    ├── MissionManager.kt      sudden-mission engine and CSV export
    ├── MissionKinds.kt        run kinds and StartGate (preview before every start)
    ├── TemiMapRepository.kt   map, locations, cloud map list and switching (temi SDK)
    ├── MapModels.kt           map snapshot and free/wall grid (auto-detects the data layout)
    ├── PathPlanner.kt         predicts the drive (A* + safety margin) for the route preview
    ├── RoutePreview.kt        builds the preview of a route
    ├── SimMaps.kt             sample maps for the simulated robot
    ├── SensorHistory.kt       last hour of readings for the Analytics graphs
    ├── RoutineManager.kt      routine scheduler, popup flow and temp missions (Routine tab)
    ├── IaqDataStore.kt        IAQ-IEQ routine data store and Google Sheets upload
    ├── StartupChecks.kt       startup self-checks
    └── IAQCalculator.kt / IAQConfig.kt   scoring
docs/                          AI bridge, IAQ-IEQ routine, Pi publisher example
apps_script/Code.gs            Google Apps Script that receives the uploaded data
```

## Troubleshooting

|Symptom|Check|
|-|-|
|Startup: "MQTT Connection" red|Pi's IP/port correct? Mosquitto running and listening on 1883 for other hosts? Tablet and Pi on the same network?|
|MQTT connected but "Sensor Communication" red|The Pi is not publishing to the configured topic. Test with `mosquitto_sub -h <broker-ip> -t 'pi/#' -v`.|
|MQTT connected, sensors red|Settings → Raspberry Pi Sensors now shows how many messages arrived, the last topic and payload, and why a message was rejected. The app listens to both `<topic>` and `<topic>/#`.|
|"Air Quality Sensors" amber, some missing|The JSON is missing those keys or uses unrecognised names.|
|"Robot Navigation" red|The app is not running on a temi, or the temi SDK is not connected. Use the simulated robot for testing elsewhere.|
|Map tab: "temi did not return a map"|Tap **Allow map access** and accept the prompt on temi; make sure a map is saved on the robot and it is online.|
|Route preview says it cannot read the map / map upside down|Map tab → Map display: set Row order or Free space / walls by hand.|
|"Robot Navigation" amber: no saved locations|Save locations on temi, then use Settings → Refresh locations from temi.|
|Mission refuses to start: "Not saved on temi: …"|A route contains a location name that does not exist on the robot's map. Re-pick it in Settings.|
|Stops fail with "timed out"|Raise the navigation timeout, or check for obstacles or a lost map on temi.|

## Known limitations

* The overall IEQ value on the dashboard is currently `(temperature + humidity) / 2`; it is not a standard comfort index.
* Temperature and humidity are scored as "middle is best", and the score is not continuous at the second breakpoint. Light and sound are scored as "middle is best" in the overall IAQ but shown as "lower is better" on the sensor cards.
* The Analytics history lives in memory for the last hour; the mission CSV files and the Google Sheet are the only saved record.
* The route preview is a prediction on the saved map, not temi's own planner. The map layout detection, the layer names used as location names and the safety margins have to be checked once on the real robot.
* Routines only run while the app is open, and waiting temp missions are not saved (see the Routine tab section).
* The sensor connection is not encrypted (plain MQTT on a local network).
* Test the first missions and routines with the robot in a clear area and keep the Stop button within reach.

