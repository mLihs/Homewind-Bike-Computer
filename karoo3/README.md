# Homewind for Karoo

Karoo 3 extension for [Homewind](https://homewind.io), the open-source fan controller for indoor cyclists: it turns the Xiaomi fan you already own into a training fan that follows your heart rate or power. The extension shows Homewind's fan state in Karoo data fields and changes fan speed and fan mode from the Karoo, from SRAM AXS bonus buttons or Wireless Blips.

**Status: 1.1.4.** Uses Homewind **2.2**'s state API (`GET /api/v1/state`, no WebSocket slot); works with Homewind 2.0 and 2.1 over the WebSocket.

Karoo 3 only. Karoo and Homewind must be in the same Wi-Fi. Install, requirements and FAQ for riders: [main README](../README.md#homewind-for-karoo-3).

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
| **Homewind apps** | Badges **APP** and **Trainer**; in a full-width cell (from 240 dp) the trainer speed on the left, Touch colours: green = connected/ready, orange = was connected, now lost, grey = not yet. The Trainer badge is hidden without a mirrored trainer. **Tap: full-screen QR code** to the Homewind web UI (scan with a phone to change settings), tap again to close |

**Sensors** (what Homewind relays; value in the sensor colour: HR pink, power violet, cadence blue, speed yellow; grey when disconnected)

| Field | Content |
| --- | --- |
| **Homewind sensors** | One line per set-up sensor: icon, source name, value |
| **Homewind sensor · HR / power / cadence / speed** | One sensor as a card |

Speed is the trainer's speed (km/h, one decimal) and needs Homewind 2.2 (`speed=` in `/api/v1/state`) and a mirrored trainer. Otherwise the speed card shows *Homewind 2.2* or *No trainer*. Speed is not in the Sensors field (three rows fit, a fourth is cut off); it has its own card and sits in the wide Apps field. Power source: power meter while it delivers, otherwise the trainer. Cadence source: cadence sensor, else power meter (once it sent cadence), else trainer (Touch concept rules).

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

1. Install the APK on the Karoo: share the APK link from the [latest release](https://github.com/mLihs/Homewind-Bike-Computer/releases/latest) to the Hammerhead Companion app (or build it yourself, see "Build and install").
2. Open **Homewind** in the Karoo's app list.
3. **Search Homewind** finds it with mDNS (`homewind-xxxxxxxx`). If nothing is found (some routers block multicast), type the IP address. The Homewind web UI or the router shows it. A fixed IP for Homewind in the router helps.
4. **Test and save**. On failure the screen shows the reason.
5. The setup screen shows a QR code to the Homewind web UI once an address is saved.
6. Optional: **Diagnosis → Live test** (bottom of the setup screen) opens the same connection as the data fields for 12 s and shows the interface (state API or WebSocket), what arrives (mode, fan %, HR range, fans) or the error.
7. Add a Homewind field to a ride profile and/or map the bonus actions.

The extension connects only while a Homewind field is on screen or a ride is recording, and disconnects 10 s after the last of them is gone (page swipes do not reconnect). A bonus action without a connection opens it for that action.

## How it talks to Homewind

Homewind 2.2 has a state endpoint; the extension uses it when present and falls back to the WebSocket for 2.0/2.1 (`/api/v1/state` answers 404 there). The setup screen's live test shows which one is in use.

| Purpose | Homewind 2.2 | Homewind 2.0 / 2.1 (fallback) |
| --- | --- | --- |
| State | `GET /api/v1/state` every 2.5 s, `key=value` lines (see `core/StateParser.kt`); no WebSocket slot | WebSocket `ws://<host>/ws` (frames 0x02, 0x03, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10) plus `telemetry/refresh?client_id=` once as second client |
| Plus / minus | `POST /api/v1/action/fan/step?dir=up\|down`; `up` = more wind (range −5), as on the Touch. 409 `ERR:limit_min`/`ERR:limit_max`: key inactive, no message; other 409 (maintenance): "Busy" | `heartrate/setmin` and `setmax` (lowering sets min first, raising max first) |
| Mode | `POST /api/v1/action/fanmode/cycle`; Homewind skips a blocked Hybrid, then the next state shows `mode` and `effective` | `fanmode/set?mode=`; on 409 for Hybrid the extension sets Pure |
| Connection test | `GET /api/v1/system/info` | same |

All POSTs send `X-Homewind: 1` (Homewind's CSRF check; without it 403). The orange "was connected" badges are computed in the extension from consecutive polls; a drop shorter than one poll interval is not seen.

**Not released yet:** Karoo polling plus two browsers on the WebSocket at the same time is cleared only after the heap test (gate G7, `docs/HEAP_BENCHMARK.md` in the Homewind repo).

## Limits with Homewind 2.0 / 2.1

- **Takes a browser slot.** Homewind 2.0 has no HTTP read for fan mode, fan speed or the HR range; only the WebSocket carries them. Homewind allows two WebSocket clients (`HW_WEBSOCKET_MAX_CLIENTS = 2`). While the Karoo is connected, only one browser fits: a second browser gets "max clients reached" (seen in the first test). If two browsers were there first, the Karoo shows *Web UI full* and tries again after 5 s, then every 30 s.
- **Two NVS writes per tap.** 2.0 writes HR min and max separately and at once. Homewind 2.1 brings the shift request with a 3 s write debounce.
- **Minus acts with delay in Smart and Hybrid.** 2.0 keeps the cool-down brake; the one-time hold release comes with 2.1.
- **Fan dots by order, not by slot.** `FANS_SNAPSHOT` lists configured fans without gaps. With an empty slot in the middle the dots shift left.
- **Limit state derived.** Without `limit=` the extension greys out a key from the HR range and the 50/200 bpm limits.

## Troubleshooting

- **mDNS search is not always reliable** (the search holds a multicast lock). Entering the IP always works; give Homewind a fixed IP in the router.
- **"Offline", `EHOSTUNREACH`, search finds nothing:** the Karoo got no ARP answer from Homewind. Seen with a FRITZ!Repeater in Wi-Fi bridge mode (Karoo on 5 GHz at one access point, Homewind on 2.4 GHz at the other): unplugging the repeater fixed it at once (2026-11-22). Check in the FRITZ!Box under Home Network → Mesh which access point each device uses; a repeater on a LAN bridge (cable) or Mesh Steering off helps. The setup test and the fields retry `EHOSTUNREACH` three times after 1 s.
- **"Wi-Fi off":** turn Wi-Fi on in the ride app's Quick Settings.
- **"Web UI full":** only with Homewind 2.0/2.1 (WebSocket); close a browser tab.

## Later

- Device ID matching (`device_id` in `system/info`, Homewind 2.2) to tell two Homewinds apart and update a changed IP.
- Router DNS step (`homewind-xxxxxxxx`) and a subnet sweep button when mDNS finds nothing.
- Gratuitous ARP in Homewind if the measurement confirms missed ARP (feature request 6).

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
