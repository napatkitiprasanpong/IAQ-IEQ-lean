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
|**Dashboard**|Overall IAQ gauge and level, nine sensor cards, and a Mission Control panel.|
|**Analytics**|Live gauges for every sensor.|
|**Mission**|Pick a route, start or stop it, and follow progress stop by stop with the readings taken at each one.|
|**Routine**|Daily schedule: at a set time temi drives a route, says and shows a reminder (for example "Please drink water") at each stop, and comes back later for anyone who asked for more time (see below). An **IAQ-IEQ routine** type instead collects sensor data at every stop and uploads it to Google Sheets (see [`docs/IAQ_IEQ_ROUTINE.md`](docs/IAQ_IEQ_ROUTINE.md)).|
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

**Message format.** Publish JSON to the topic (default `iaq/sensors`), about every 2 seconds:

```json
{"temp":24.1,"humidity":55,"co2":620,"pm25":9,"pm10":14,"tvoc":0.2,"hcho":0.02,"light":310,"sound":42}
```

* Missing keys keep their previous value.
* Alternative key names are accepted (for example `temperature`, `rh`, `pm2_5`, `lux`, `noise`).
* The values can also sit inside a `"sensors"` or `"data"` object.
* One sensor per topic also works: set the topic to `iaq/sensors/#` and publish a plain number to `iaq/sensors/co2`, `iaq/sensors/temp`, and so on.

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
3. **temi Robot & Mission:** set the sampling time per stop, the navigation timeout, retries, home base name, whether to return home at the end, and **Start this app automatically when temi starts** (see below).

#### Starting the app automatically

Switch on **Start this app automatically when temi starts** (Settings → temi Robot & Mission). It does two things:

* It registers the app as temi's **start-up (kiosk) app** through the temi SDK (`requestToBeKioskApp`). temi may show a confirmation on its screen; accept it. Under the switch the app shows whether temi's launcher has this app selected. This is the route that also works on newer Android versions.
* It makes the app open itself when the tablet finishes booting (a boot receiver). On Android 10 and newer, Android blocks apps from opening screens in the background, so this part may do nothing there. The tablet in this project runs Android 6, where it works.

Turn the switch off to stop the boot receiver. If temi's launcher keeps opening the app, change the start-up app in temi's own settings. Test it once by restarting temi.

Check the **Startup** screen: every card should be green.

## Running a mission

1. Open **Dashboard** or **Mission**, choose a route and tap **Start Mission**.
2. For each stop, temi drives to the location and waits for arrival. If navigation fails it retries (up to the configured number), then marks the stop as failed and continues.
3. On arrival, the app waits for the sampling time, then records the IAQ score and all nine readings.
4. At the end temi optionally returns to home base.
5. **Stop Mission** cancels the current movement and ends the mission. Stops already sampled are kept.

A mission cannot start if temi is not connected, the route is empty, or a location is not saved on temi (the app tells you which one).

### Results file

Each mission writes a CSV to the app's external files folder under `missions/`, for example:

```
/sdcard/Android/data/com.leslie.iaqieqmornitoring/files/missions/mission_20261021_101500_Route_A.csv
```

The exact path is shown on the Mission screen. Columns:

`time, route, stop, location, outcome, iaq, level, temp_c, humidity_pct, co2_ppm, pm25, pm10, tvoc, hcho, light_lux, sound_db, data_fresh, note`

`data_fresh` is `false` if no sensor message arrived in the 15 seconds before the reading was recorded, so stale data can be told apart from real data.

## Routine tab

The **Routine** tab sets up reminders that temi delivers in person. A routine is a **time** plus a **mission** (one of the routes from Settings). The message is "Please drink water" by default and can be changed per routine.

### How a routine runs

1. At the set time, every day, temi starts the route. (The app must be open; see the limits below.)
2. At each stop temi **speaks the message** and a **popup** appears on top of whichever tab is open, with two buttons:

   * **Yes**: temi carries on to the next stop.
   * **Come back in N mins**: temi also carries on to the next stop, and a **temp mission** is created for that stop.
3. After the last stop temi **returns to home base**. For every temp mission an **N-minute timer** now starts.
4. When a timer ends, temi drives to that one stop and asks again.

   * **Yes**: temi returns to home base and that temp mission is cleared.
   * **Come back in N mins**: temi returns to home base and a **new temp mission** with a new timer replaces it.
5. When there are no temp missions left, **the routine is done**.

`N` is 5 by default and can be changed in **Routine settings** (1 to 60 minutes), which is handy for testing.

### Using the tab

* **Add routine:** enter a name (optional), the time as `HH:MM` (24 h), pick the mission (route), and edit the message if you like. Routines have an on/off switch and can be deleted.
* **Run now** starts a routine immediately, without waiting for its time.
* **Routine status** shows what temi is doing. **Stop routine** stops temi and discards all waiting temp missions.
* **Temp missions** lists each one with its countdown ("timer starts at home base" until temi is back home). **History** logs the answers and events.
* **Popup waits for an answer** (10 to 300 s, default 60 s): if nobody taps a button in time, it counts as **Come back in N mins**, so temi never waits forever.

### Rules and limits

* The route, locations, home base, retries and navigation timeout all come from **Settings**. A routine will not start if temi is not connected, the route is empty, or a location is not saved on temi.
* temi always returns to home base at the end of a routine, whatever the "return home" setting says.
* A routine only starts if it is within 10 minutes of its time and no mission or other routine is running. It starts at most once per day. A temp mission whose timer ends during an inspection mission waits until that mission is over.
* While a routine is driving or asking, the Mission tab refuses to start a mission.
* If a stop cannot be reached, it is skipped and noted in the History. Nothing is retried beyond the usual navigation retries.
* Routines only run **while the app is running**: there is no background service and no start at boot. The schedule is saved, but waiting temp missions are **lost if the app is closed**.
* The popup appears inside the app. If the app is in the background, temi still speaks but no popup is visible.
* With **Simulated robot** on, the whole flow runs on any tablet; nothing is spoken.

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
├── screens/                   Startup, Dashboard, Analytics, Detail, IAQI scale, Mission, Routine, Settings
├── bridge/                    AI/voice bridge (commands in, status out)
├── components/                gauges, cards, top bar, sidebar, shared form widgets, RoutinePromptHost (the routine popup)
└── utils/
    ├── AppSettings.kt         persisted settings and routes
    ├── ThresholdStore.kt      editable IAQ breakpoints
    ├── SensorRepository.kt    current readings, link state, simulator
    ├── PiSensorClient.kt      MQTT client and payload parser
    ├── TemiRepository.kt      the only class that uses the temi SDK (plus simulated robot)
    ├── MissionManager.kt      mission engine and CSV export
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
|MQTT connected but "Sensor Communication" red|The Pi is not publishing to the configured topic. Test with `mosquitto_sub -h <pi-ip> -t 'iaq/#' -v`.|
|MQTT connected, sensors red|Settings → Raspberry Pi Sensors now shows how many messages arrived, the last topic and payload, and why a message was rejected. The app listens to both `<topic>` and `<topic>/#`.|
|"Air Quality Sensors" amber, some missing|The JSON is missing those keys or uses unrecognised names.|
|"Robot Navigation" red|The app is not running on a temi, or the temi SDK is not connected. Use the simulated robot for testing elsewhere.|
|"Robot Navigation" amber: no saved locations|Save locations on temi, then use Settings → Refresh locations from temi.|
|Mission refuses to start: "Not saved on temi: …"|A route contains a location name that does not exist on the robot's map. Re-pick it in Settings.|
|Stops fail with "timed out"|Raise the navigation timeout, or check for obstacles or a lost map on temi.|

## Known limitations

* The overall IEQ value on the dashboard is currently `(temperature + humidity) / 2`; it is not a standard comfort index.
* Temperature and humidity are scored as "middle is best", and the score is not continuous at the second breakpoint. Light and sound are scored as "middle is best" in the overall IAQ but shown as "lower is better" on the sensor cards.
* The app does not keep a long-term history of readings; the mission CSV files are the only saved record.
* Routines only run while the app is open, and waiting temp missions are not saved (see the Routine tab section).
* The sensor connection is not encrypted (plain MQTT on a local network).
* Test the first missions and routines with the robot in a clear area and keep the Stop button within reach.

