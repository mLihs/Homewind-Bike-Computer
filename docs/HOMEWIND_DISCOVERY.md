# HOMEWIND_DISCOVERY.md — finding and keeping the Homewind connection

Status 2026-11-22: design, not implemented. Target: Karoo 3 extension
(Android 12, karoo-ext). Firmware-side counterpart in the Homewind repo:
`Homewind/docs/AGENT_NETWORK_REACHABILITY.md`.

## Problem

- The extension finds Homewind via mDNS (`_http._tcp`, `homewind-…`) only
  sometimes, and sometimes gets an immediate
  `ConnectException: Failed to connect to /192.168.178.40:80`.
- At the same time, browsers on several devices reach Homewind by IP
  without problems.

## Cause (short)

- Browsers reuse a known IP over unicast; lost frames are retried by Wi-Fi
  and TCP. mDNS is multicast: sent once, never retried. A missed browse says
  nothing about whether Homewind is reachable.
- The extension depends on mDNS too often and treats a failed search as
  "offline".
- Homewind starts mDNS twice per Wi-Fi connect and publishes no device ID
  (fixed in a later firmware, see "Firmware versions" below).
- An immediate `ConnectException` (no timeout) on a LAN IP is most likely
  `ENETUNREACH` (Karoo Wi-Fi down or reconnecting) or a refused connection
  (stale IP now owned by another device). A failed ARP resolution gives
  `EHOSTUNREACH` only after ~3 s. Unconfirmed until errno is logged (0.4.1).
- ARP loss on the Homewind side is a hypothesis under measurement (Homewind
  `docs/AGENT_NETWORK_REACHABILITY.md`, Task 0). Homewind runs with Wi-Fi
  modem sleep off.

## Principle

Discovery only produces an IP. After that the extension works like the
browser: it keeps using the saved IP. A failed search never marks Homewind
offline.

## Identity

Stored per device:

| Field | Source | Notes |
|---|---|---|
| `lastIp` | any successful probe | updated whenever found elsewhere |
| `hostId8` | mDNS name `homewind-xxxxxxxx` | not unique (≈1/256 collision between boards) |
| `deviceId` | `device_id` (12-hex MAC) from `system/info` or mDNS TXT `id` | unique; only with new firmware |
| `lastSeen`, `fwVersion` | probe | diagnostics |

Match rule for a probe response:
1. Response has `device_id`: accept only if it equals the saved
   `deviceId`; adopt it if none is saved yet.
2. No `device_id` (current firmware): accept if `name=Homewind`.
3. Several Homewinds without `device_id` in one search: let the user pick
   (show IP and version). Never take the first `homewind-*` silently.

## Probe

`GET http://<ip>/api/v1/system/info`, connect timeout 1.5 s, read timeout
2 s. Response is `key=value` lines (`name=Homewind`, `version=…`, and with
new firmware `device_id=…`, `hostname=…`).

| Result | Meaning |
|---|---|
| 200 + match | found |
| 200, no match | another device or another Homewind at this IP |
| 503 | Homewind in maintenance/OTA: present, busy; do not re-discover |
| timeout / refused / unreachable | not here (now) |

Action POSTs need the header `X-Homewind: 1`, otherwise 403.

## Discovery chain

Run in order; stop at the first match.

1. **Saved IP**: probe `lastIp`.
2. **Router DNS (optional, test first)**: resolve the Homewind name with the
   normal resolver (`InetAddress.getAllByName`), then probe. Needs Homewind
   2.2 (correct DHCP name) and a router that registers DHCP names.
   - A bare name (`homewind-<hostId8>`) is often not resolved on Android,
     because the DHCP search domain is not reliably appended.
   - `homewind-<hostId8>.fritz.box` works only in FRITZ!Box networks.
   - Test both forms on the Karoo before enabling this step.
3. **mDNS**: `NsdManager.discoverServices("_http._tcp", …)` for 10–15 s.
   Filter service names starting with `homewind-`, prefer the saved
   `hostId8` / TXT `id`. Android 12: call `resolveService` one at a time
   (parallel calls fail with `FAILURE_ALREADY_ACTIVE`). Probe each resolved
   IP before accepting it. A `MulticastLock` is not required for
   `NsdManager` but does no harm.
4. **Subnet sweep** (only on an explicit "Search" tap, never in the
   background): take the Wi-Fi IPv4 address and prefix from `LinkProperties`,
   scan the own /24 (also if the prefix is shorter) on port 80 with the probe,
   about 32 in parallel, 300–500 ms connect timeout, ≈5 s total.
   - Independent of mDNS and of the firmware version, **not** of ARP: each
     connect needs an ARP broadcast first. If Homewind misses ARP, the sweep
     fails like the saved IP.
   - Useful for first setup and after an IP change. For IP changes, a fixed
     IP in the router (FRITZ!Box: "Diesem Netzwerkgerät immer die gleiche
     IPv4-Adresse zuweisen") is simpler; mention it in the setup help.
5. **Manual IP** entry in the settings, validated with the probe.

The first successful step updates `lastIp` (and `deviceId` when present).

## Runtime (connected or reconnecting)

- HTTP and WebSocket (`ws://<ip>/ws`) always use `lastIp`.
- On failure: after `EHOSTUNREACH`, retry after 1 s, up to 3 times; then,
  and for all other errors, backoff 3 s → 30 s. Keep showing the last values
  marked as stale, not "Offline", for the first failures.
- Re-run discovery steps 2–3 only after 3 failures in a row, at most once
  per minute. Never during 503 or within 60 s after a Homewind restart.
- If discovery finds the device at a new IP, switch and save.
- Show "Offline" only after discovery found nothing **and** `lastIp` has
  failed for ≥ 2 min.
- Wait for Karoo Wi-Fi to be connected (`ConnectivityManager` network
  callback with `TRANSPORT_WIFI`) before probing; probing without Wi-Fi only
  produces `ENETUNREACH`.
- Bind HTTP and WebSocket to the Wi-Fi `Network` (`network.socketFactory` /
  OkHttp `socketFactory`). Karoo 3 has no cellular, so this is insurance,
  not the fix.
- Log for every failure: exception type, errno text, Wi-Fi connected yes/no,
  IP tried, discovery step.
- Show the reason under "Offline" in plain text. Name the observed error,
  not an unproven cause:

| Error | Text |
|---|---|
| Wi-Fi not connected / `ENETUNREACH` | "Karoo not connected to Wi-Fi" |
| `EHOSTUNREACH` | "Homewind not reachable (EHOSTUNREACH)" |
| `ECONNREFUSED` | "Connection refused (ECONNREFUSED) – IP may belong to another device" |
| timeout | "Homewind not answering (timeout)" |
| 503 | "Homewind busy (update or maintenance)" |
| 200, wrong `device_id` | "Another device at this IP" |

## Android and future versions

- Cleartext HTTP: `android:usesCleartextTraffic="true"` or a `base-config`
  in `network_security_config` (a `domain-config` cannot whitelist IPs).
- Local network permission (`ACCESS_LOCAL_NETWORK`, reported for Android 17
  / API 37): **open, not verified**. Relevant only when the target SDK is
  raised or Hammerhead updates the OS. Nothing to build now.
- `NsdManager` on Android 14+: use `registerServiceInfoCallback` instead of
  `resolveService`. Keep both paths behind one interface.

## Firmware versions

`device_id` and the correct DHCP name are requested for Homewind **2.2**.
Until then the extension cannot tell two Homewinds apart by HTTP, and
cannot detect an IP that was handed to another device running a web server
that also answers `name=Homewind` (practically: another Homewind).

| Step | Homewind today (2.1.x) | Homewind 2.2 |
|---|---|---|
| Saved IP | yes, match by `name=Homewind` | yes, match by `device_id` |
| Router DNS | no (router knows `esp32s3-XXXXXX`) | if the Karoo test passes |
| mDNS | yes, no TXT | yes, TXT `id`, `api`, `version` |
| Subnet sweep | yes | yes |
| Manual IP | yes | yes |

The chain works with today's firmware; the firmware fixes only add steps
and a unique ID. Parse `system/info` and TXT tolerantly (ignore unknown
keys, missing keys allowed).

## Releases

**0.4.1** (works with Homewind 2.1.x):
- Log errno and Wi-Fi state; show the plain-text reason under "Offline".
- Treat 503 as "busy".
- Bind HTTP and WebSocket to the Wi-Fi network.
- After `EHOSTUNREACH`, retry after 1 s (max 3), then normal backoff.

**Later** (after Homewind 2.2 and the ARP measurement):
- Match by `device_id`; mDNS TXT `id`.
- Router DNS step, if the Karoo test passes.
- Subnet sweep as "Search" button for first setup.

## Tests

Unit (JVM, no device):
- Discovery chain with fake DNS resolver, fake NSD source and MockWebServer
  for `system/info`: match, wrong `device_id`, 503, timeout, two Homewinds
  without ID.
- Probe parser: today's response, response with `device_id`/`hostname`,
  unknown keys, truncated response.
- Subnet sweep: address range from IP/prefix (/24, /23, /16), parallel
  limit, overall time bound.
- Backoff and offline rules: stale vs. offline timing, no re-discovery
  during 503.

Field (Karoo 3, FRITZ!Box, Homewind with BLE sensors and Zwift connected,
no browser open anywhere):
- "Test and save" 10 of 10.
- Homewind restart during a ride: values resume without user action.
- New IP via DHCP (delete lease / reserve another IP): found again
  automatically.
- Karoo Wi-Fi off/on, Karoo sleep/wake.
- Two Homewinds in the same network (if available).

## Effort

| Part | Estimate |
|---|---|
| Identity, probe, discovery chain, sweep | 1–1.5 days |
| Runtime backoff, offline rules, Wi-Fi binding, logging | 0.5–1 day |
| Unit tests | 0.5–1 day |
| Field test and reproducing the `ConnectException` | 0.5–1 day |
| **Total** | **3–4 days** |

## Open

- Exact cause of the immediate `ConnectException`: confirm with the
  failure log above.
- Whether Homewind misses ARP requests (Homewind Task 0). If yes, Homewind
  adds gratuitous ARP; it only refreshes an existing ARP entry on the Karoo,
  so the 1 s retry stays useful.
- Bare-name and `.fritz.box` resolution on the Karoo.
- Local network permission on future Android versions.
- Whether the Karoo connects through a FRITZ!Box mesh repeater while
  searching (multicast across repeaters).
