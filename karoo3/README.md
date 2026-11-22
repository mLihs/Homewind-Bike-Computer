# Homewind for Karoo

Karoo 3 extension for [Homewind](https://github.com/mLihs/Homewind). It shows Homewind's fan state in Karoo data fields and changes fan speed and fan mode from the Karoo, from SRAM AXS bonus buttons or Wireless Blips.

**Status: MVP 0.1.0.** Works with Homewind **2.0** as it is: no firmware change needed. Release 2 needs new Homewind endpoints (see below).

Karoo 3 only. Karoo and Homewind must be in the same Wi-Fi.

**Wi-Fi in the ride app:** the Karoo turns Wi-Fi off when you open the ride app. Turn it on there in the Quick Settings (swipe down); it then stays on while recording (tested 2026-11-21). The field shows *Wi-Fi off* until it is on and connects within seconds after.

## Data fields

Find them in the profile editor: tap a field → filter **Extensions** → Homewind. The previews in the field picker show animated example data (a ride building up and easing off), without connecting to Homewind. Look as the Karoo's own fields (icon top left, title in capitals top right, value bottom right), colours from the Homewind Touch.

**Fan**

| Field | Content |
| --- | --- |
| **Homewind fan · numeric** | FAN · mode, fan dots, fan speed in % (green while running) |
| **Homewind fan · zone colour** | Whole tile in 10 % step colours from the Homewind palette, white text |
| **Homewind fan · control** | Wide: minus, mode (tap to switch), fan speed, fan dots, plus; bottom line "HR 80–160 · FTP 250" (FTP only in Hybrid), so every plus/minus tap shows the new range. In low cells the dots are left out |
| **Homewind fan · minus plus** | The two keys |
| **Homewind fan · mode** | MODE; tap switches Pure → Smart → Hybrid |
| **Homewind fan · plain number** | Fan speed as a plain Karoo number field |

Fan dots, Touch colours: green = fan runs in the control, white = connected but off, orange = not reachable or in error, dark grey = no fan in this slot.

**Graphs** (last 10 minutes, newest on the right; one sample every 2 s while the Karoo is connected)

| Field | Content |
| --- | --- |
| **Homewind graph · fan and HR** | Fan speed as green area, heart rate as pink line |
| **Homewind graph · zones** | Fan speed as bars in the 10 % step colours, heart rate as white line |

Step colours (Homewind palette, cadence blue → HR pink): 0 % `#0E7FA3`, 10 % `#27739C`, 20 % `#406695`, 30 % `#595A8F`, 40 % `#724D88`, 50 % `#8C4181`, 60 % `#A5347A`, 70 % `#BE2874`, 80 % `#D71B6D`, 90 % and up `#F00F66`.

Both draw Homewind's HR range (HR min/max, where the fan ramps from minimum to maximum) as faint dashed lines; the header shows the current fan speed and heart rate. The history lives in memory only and starts when a Homewind field is on screen or a ride is recording.

**Apps and trainer**

| Field | Content |
| --- | --- |
| **Homewind apps** | Badges **APP** and **Trainer**, Touch colours: green = connected/ready, orange = was connected, now lost, grey = not yet. The Trainer badge is hidden without a mirrored trainer. **Tap: full-screen QR code** to the Homewind web UI (scan with a phone to change settings), tap again to close |

**Sensors** (what Homewind relays; value in the sensor colour: HR pink, power violet, cadence blue; grey when disconnected)

| Field | Content |
| --- | --- |
| **Homewind sensors** | One line per set-up sensor: icon, source name, value |
| **Homewind sensor · HR / power / cadence** | One sensor as a card |

Power source: power meter while it delivers, otherwise the trainer. Cadence source: cadence sensor, else power meter (once it sent cadence), else trainer (Touch concept rules).

Field texts when there is no data: *Set up* (no address), *Wi-Fi off* (Karoo Wi-Fi off), *Offline* (no answer), *Busy* (Homewind answers 503: update or maintenance), *Web UI full* (two browsers already connected, see "Limits"). Below it the observed error in plain text (0.4.1, see `docs/HOMEWIND_DISCOVERY.md`):

| Error | Text |
| --- | --- |
| Wi-Fi not connected / `ENETUNREACH` | Karoo not connected to Wi-Fi |
| `EHOSTUNREACH` | Homewind not reachable (EHOSTUNREACH) — retried after 1 s, up to 3 times |
| `ECONNREFUSED` | Connection refused (ECONNREFUSED) – IP may belong to another device |
| timeout | Homewind not answering (timeout) |
| 503 | Homewind busy (update or maintenance) |

Every failure is logged (`adb logcat -s HomewindHttp HomewindField`): kind, errno, Wi-Fi state, IP, step and the exception chain. HTTP and WebSocket are bound to the Wi-Fi network.

**Rule for field texts:** no `%` in display names or descriptions followed by more text. The Karoo sideload installer failed with "Unknown error" on 0.2.1, the first version with such a text.

## Bonus actions

Map them on the Karoo: Sensors → your AXS groupset → Configure Controls → Karoo Action → Homewind.

| Action | Effect |
| --- | --- |
| **Homewind +** | More wind: HR min and HR max −5 bpm |
| **Homewind −** | Less wind: HR min and HR max +5 bpm |
| **Homewind mode** | Pure → Smart → Hybrid → Pure. Hybrid is skipped when Homewind does not allow it (no power source, HR span < 30 bpm) |

Plus and minus behave like the Touch: the span stays the same, nothing happens at the limits 50 and 200 bpm, the fan speed in % is the feedback.

Which buttons can carry Karoo actions: bonus buttons on SRAM RED AXS and Force AXS shifters (short and long press), AXS Wireless Blips (short press only). Plain shift paddles cannot. Without these, use the **Homewind control** field.

## Setup

1. Install the APK on the Karoo (see "Build and install").
2. Open **Homewind** in the Karoo's app list.
3. **Search Homewind** finds it with mDNS (`homewind-xxxxxxxx`). If nothing is found (some routers block multicast), type the IP address. The Homewind web UI or the router shows it. A fixed IP for Homewind in the router helps.
4. **Test and save**. On failure the screen shows the reason.
5. The setup screen shows a QR code to the Homewind web UI once an address is saved.
6. Optional: **Live test** opens the same connection as the data fields for 12 s and shows what arrives (mode, fan %, HR range, fans) or the error.
7. Add a Homewind field to a ride profile and/or map the bonus actions.

The extension connects only while a Homewind field is on screen or a ride is recording, and disconnects 10 s after the last of them is gone (page swipes do not reconnect). A bonus action without a connection opens it for that action.

## How it talks to Homewind (2.0)

| Purpose | Homewind 2.0 interface |
| --- | --- |
| State | WebSocket `ws://<host>/ws`: `SENSORS_SNAPSHOT` (0x02), `SENSOR_CONN_STATE` (0x03), `HEART_RATE` (0x06), `FANS_SNAPSHOT` (0x07), `HEART_RATE_SETTINGS` (0x0B), `CLIENT_ID` (0x0C), `BIKE_METRICS` (0x0D), `FAN_CONTROL` (0x0E), `TRAINER_STATUS` (0x0F), `APP_STATUS` (0x10) |
| Fan list, sensors, trainer as second client | `POST /api/v1/action/telemetry/refresh?client_id=<id>` (once, only if they did not arrive) |
| Plus / minus | `POST /api/v1/action/heartrate/setmin?value=` and `setmax?value=`. Lowering sets min first, raising sets max first, so the firmware's gap clamp cannot change either value |
| Mode | `POST /api/v1/action/fanmode/set?mode=pure\|smart\|hybrid`. On 409 for Hybrid the extension sets Pure |
| Connection test | `GET /api/v1/system/info` |

All POSTs send `X-Homewind: 1` (Homewind's CSRF check).

## Limits of the MVP

- **Takes a browser slot.** Homewind 2.0 has no HTTP read for fan mode, fan speed or the HR range; only the WebSocket carries them. Homewind allows two WebSocket clients (`HW_WEBSOCKET_MAX_CLIENTS = 2`). While the Karoo is connected, only one browser fits: a second browser gets "max clients reached" (seen in the first test). If two browsers were there first, the Karoo shows *Web UI full* and tries again every 30 s.
- **mDNS search is not always reliable** on the Karoo (0.1.2 holds a multicast lock during the search). Entering the IP always works; give Homewind a fixed IP in the router.
- **Two NVS writes per tap.** 2.0 writes HR min and max separately and at once. Homewind 2.1 brings the shift request with a 3 s write debounce.
- **Minus acts with delay in Smart and Hybrid.** 2.0 keeps the cool-down brake; the one-time hold release comes with 2.1.
- **Fan dots by order, not by slot.** `FANS_SNAPSHOT` lists configured fans without gaps. With an empty slot in the middle the dots shift left.
- **No limit state.** At 50/200 bpm a key simply does nothing (feature request: inactive keys).
- **Placeholder icon.** `ic_homewind.xml` is a placeholder; replace it with the Homewind fan icon.

## Release 2 (with Homewind 2.2)

Needs on the Homewind side:
- `GET /api/v1/karoo/state`: one short key=value line with fixed buffer (`v=1`, mode, effective, fan %, four fan states, HR min/max, limit). The Karoo then polls over HTTP every 2–3 s and **no longer uses a WebSocket slot**.
- `POST /api/v1/action/fan/step?dir=up|down`: the 2.1 shift request (one call, one debounced write).
- `POST /api/v1/action/fanmode/cycle`: cycle with the shared Hybrid check.
- Optional `MDNS.addService("homewind", "tcp", 80)` for a cleaner search.

Then the extension switches from WebSocket to HTTP polling and reads `v=` to stay compatible with 2.0/2.1.

## Build and install

Requirements: JDK 17, Android SDK (Android Studio or command line tools).

karoo-ext is not on Maven Central. Two ways:

**A. Without a GitHub token (local Maven):**
```sh
git clone --branch 1.1.9 https://github.com/hammerheadnav/karoo-ext
cd karoo-ext && ./gradlew :lib:publishToMavenLocal
```

**B. GitHub Packages:** a GitHub token with `read:packages`, in `~/.gradle/gradle.properties`:
```
gpr.user=<github user>
gpr.key=<token>
```

Build (in `karoo3/`):
```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (Karoo via USB-C, developer mode), or sideload through the Hammerhead Companion app.

## Test checklist (first ride)

1. Setup: search finds Homewind; IP entry works; *Test and save* shows name and version.
2. Fields: mode, %, dots match the Homewind web UI. Switch a fan off in the web UI → its dot turns white.
3. Plus/minus (field and AXS): range moves by 5 bpm in the web UI; % follows; at 50/200 nothing happens.
4. Mode: Pure → Smart → Hybrid; without a power source Hybrid is skipped.
5. Browser slots: Karoo + one browser works; with two browsers open the Karoo shows *Web UI full*, and connects within 30 s after one closes.
6. Reconnect: switch Homewind off and on → *Offline*, then back without a Karoo restart.
7. Ride end: after stopping the ride and leaving the ride screen the Karoo disconnects within 10 s (web UI can take a second browser again).
8. **Heap on the XIAO** (no PSRAM): ride with HR, trainer mirror, Zwift, Karoo connected and one browser. Measure as in Homewind `docs/HEAP_BENCHMARK.md` and compare with the 2026-11-20 rows.

## Project layout

```
app/src/main/kotlin/io/homewind/karoo/
├── core/                 # plain Kotlin, unit tested: frame parser, plus/minus, mode cycle, display rules, history
├── net/
│   ├── HomewindHttp.kt   # OkHttp client (bound to Wi-Fi), POST, setup test, failure logging
│   └── WifiWatcher.kt    # Wi-Fi state for the process
├── fields/
│   ├── FieldLifecycle.kt # view lifecycle: connection hold, redraw on change, at most 1/s, error isolation
│   ├── FanFields.kt      # fan tiles, keys, mode, plain number field
│   ├── SensorFields.kt   # sensors, HR, power, cadence, apps
│   ├── GraphField.kt     # graphs
│   └── TapActions.kt     # tap callbacks (plus, minus, mode)
├── ui/                   # Glance building blocks and colours
├── HomewindExtension.kt  # KarooExtension: data types, bonus actions, ride state, alerts
├── HomewindRuntime.kt    # WebSocket, shared state, graph history
├── HomewindActions.kt    # plus, minus, mode over HTTP
├── GraphRenderer.kt      # graph bitmap
├── SettingsActivity.kt   # setup screen on the Karoo
├── Discovery.kt          # mDNS search
└── Prefs.kt
```
