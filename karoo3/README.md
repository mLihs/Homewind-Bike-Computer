# Homewind for Karoo

Karoo 3 extension for [Homewind](https://github.com/mLihs/Homewind). It shows Homewind's fan state in Karoo data fields and changes fan speed and fan mode from the Karoo, from SRAM AXS bonus buttons or Wireless Blips.

**Status: MVP 0.1.0.** Works with Homewind **2.0** as it is: no firmware change needed. Release 2 needs new Homewind endpoints (see below).

Karoo 3 only. Karoo and Homewind must be in the same Wi-Fi.

## Data fields

| Field | Content |
| --- | --- |
| **Homewind** | Mode in effect (Pure, Smart, Hybrid), fan speed in %, four fan dots |
| **Homewind control** | Same, with minus and plus keys to tap |
| **Homewind fan %** | Fan speed as a plain number field |

Fan dots, same colours as the Homewind Touch: green = fan runs in the control, white = connected but off, orange = not reachable or in error, dark grey = no fan in this slot.

Field texts when there is no data: *Set up* (no address), *Offline* (no answer), *Web UI full* (two browsers already connected, see "Limits").

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
4. **Test and save**.
5. Add a Homewind field to a ride profile and/or map the bonus actions.

The extension connects only while a Homewind field is on screen or a ride is recording, and disconnects otherwise.

## How it talks to Homewind (2.0)

| Purpose | Homewind 2.0 interface |
| --- | --- |
| State | WebSocket `ws://<host>/ws`: `HEART_RATE_SETTINGS` (0x0B), `FAN_CONTROL` (0x0E), `FANS_SNAPSHOT` (0x07), `HEART_RATE` (0x06), `CLIENT_ID` (0x0C) |
| Fan list as second client | `POST /api/v1/action/telemetry/refresh?client_id=<id>` (once, only if the list did not arrive) |
| Plus / minus | `POST /api/v1/action/heartrate/setmin?value=` and `setmax?value=`. Lowering sets min first, raising sets max first, so the firmware's gap clamp cannot change either value |
| Mode | `POST /api/v1/action/fanmode/set?mode=pure\|smart\|hybrid`. On 409 for Hybrid the extension sets Pure |
| Connection test | `GET /api/v1/system/info` |

All POSTs send `X-Homewind: 1` (Homewind's CSRF check).

## Limits of the MVP

- **Takes a browser slot.** Homewind 2.0 has no HTTP read for fan mode, fan speed or the HR range; only the WebSocket carries them. Homewind allows two WebSocket clients (`HW_WEBSOCKET_MAX_CLIENTS = 2`). While the Karoo is connected, only one browser fits. If two browsers are open, the Karoo shows *Web UI full* and tries again every 30 s.
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
7. Ride end: after stopping the ride and leaving the ride screen the Karoo disconnects (web UI can take a second browser again).
8. **Heap on the XIAO** (no PSRAM): ride with HR, trainer mirror, Zwift, Karoo connected and one browser. Measure as in Homewind `docs/HEAP_BENCHMARK.md` and compare with the 2026-11-20 rows.

## Project layout

```
app/src/main/kotlin/io/homewind/karoo/
├── core/                 # plain Kotlin, unit tested: frame parser, plus/minus, mode cycle
├── HomewindExtension.kt  # KarooExtension: data types, bonus actions, ride state, alerts
├── HomewindRuntime.kt    # WebSocket + HTTP to Homewind, shared state
├── Fields.kt             # graphical fields (Glance) and number field
├── SettingsActivity.kt   # setup screen on the Karoo
├── Discovery.kt          # mDNS search
└── Prefs.kt
```
