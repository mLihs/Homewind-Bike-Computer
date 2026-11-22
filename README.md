# Homewind Bike Computer

Bike computer apps for [Homewind](https://homewind.io) ([GitHub](https://github.com/mLihs/Homewind)).

Homewind turns the Xiaomi fan you already own into a training fan. Three modes follow your heart rate or power, it runs up to four fans, and it doubles as a sensor bridge for Zwift, Rouvy and MyWhoosh, ideal for Apple TV.

The apps here bring Homewind to the bike computer: see your wind as data fields and change it from the bars.

| Folder | Device | Status |
| --- | --- | --- |
| [`karoo3/`](karoo3/) | Hammerhead Karoo 3 (karoo-ext extension) | 1.0.1, Homewind 2.2 state API (2.0/2.1 over WebSocket) |

Planned: Garmin (Connect IQ).

## Homewind for Karoo 3

See what Homewind does and change it without letting go of the bars. Fan speed, mode, heart rate range and sensors show up as Karoo data fields. More or less wind comes from your SRAM AXS buttons, Wireless Blips or the touch screen.

- **See your wind.** Fan speed in %, mode and the status of every fan, in fields that look like the Karoo's own. Colours from the Homewind Touch.
- **Change it on the go.** Plus gives more wind, minus less: both shift your heart rate range by 5 bpm, the same as on the Touch. A third button switches Pure → Smart → Hybrid.
- **Watch the ride.** A graph shows fan speed and heart rate over the last 10 minutes, with your heart rate range as dashed lines.
- **No extra Bluetooth device.** The Karoo talks to Homewind over your Wi-Fi.

**What you need**

- Hammerhead Karoo 3. Older Karoo models are not supported.
- Homewind firmware 2.2. 2.0 and 2.1 work too, but then the Karoo uses one of Homewind's two browser connections.
- Karoo and Homewind in the same Wi-Fi. Homewind runs on 2.4 GHz, the Karoo can be on 2.4 or 5 GHz.
- Wi-Fi on during the ride: the Karoo turns it off when the ride app opens; swipe down and turn it back on.
- Optional: SRAM AXS shifters with bonus buttons or Wireless Blips.

**Set it up**

1. **Install.** On your phone, open the [latest release](https://github.com/mLihs/Homewind-Bike-Computer/releases/latest), copy the link to the APK and share it to the Hammerhead Companion app. The Karoo installs Homewind. Updates appear on the Karoo by themselves.
2. **Connect.** Open Homewind on the Karoo and tap *Search Homewind* (it shows up as `homewind-xxxxxxxx`), or type its IP address.
3. **Test and save.** The Karoo shows Homewind's name and version and a QR code to the Homewind settings.
4. **Add fields.** Profile editor → tap a field → Extensions → Homewind.
5. **Map buttons** (optional). Sensors → your AXS groupset → Configure Controls → Karoo Action → Homewind.
6. **Ride.** Turn Wi-Fi on in the ride app. The fields connect within seconds.

Give Homewind a fixed IP address in your router; then the Karoo finds it every time.

**FAQ**

- **Offline, or the search finds nothing:** usually a Wi-Fi repeater between Karoo and Homewind. In our test a FRITZ!Repeater in Wi-Fi bridge mode was the cause; unplugging it fixed it at once. Connect the repeater by cable or check which access point each device uses.
- **Wi-Fi off:** the ride app turned Wi-Fi off; swipe down and turn it on.
- **Browser slot:** with Homewind 2.2 the Karoo takes none; with 2.0/2.1 it uses one of the two.
- **Always connected?** No. Only while a Homewind field is on screen or a ride is recording, then about 10 s more.
- **Other settings:** heart rate range and mode from the Karoo; everything else in the Homewind web UI (tap the Apps field for a QR code).

All fields, buttons and technical details: [`karoo3/README.md`](karoo3/README.md).

Hammerhead, Karoo and SRAM AXS belong to their owners. Homewind is an independent project.

## Development

### Updates on the Karoo

The app declares an update manifest (`io.hammerhead.karooext.MANIFEST_URL`): `https://github.com/mLihs/Homewind-Bike-Computer/releases/download/karoo3-latest/manifest.json`. Every signed release rewrites it (release `karoo3-latest`, a fixed channel so other releases in this repo do not interfere). The Karoo compares `latestVersionCode` with the installed `versionCode` and offers the update in its Extension Library.

### Builds and releases

GitHub Actions builds and tests the Karoo APK on every push to `main` that touches `karoo3/`.

**Release:** raise `versionName` and `versionCode` in `karoo3/app/build.gradle.kts` and push. `versionCode` = major × 10000 + minor × 100 + patch (0.4.2 → 402); the workflow stops if they do not match. If there is no tag `karoo3-v<versionName>` yet, the workflow creates it and a GitHub release with `homewind-karoo3-v<version>.apk`. Pushes without a new version only build.

**Signing:** with the repository secrets below the release APK is signed with a fixed key, so new versions install over old ones. Without them the workflow ships the debug build; its key changes per build, so an update needs an uninstall first.

| Secret | Content |
| --- | --- |
| `KAROO_KEYSTORE_BASE64` | the keystore file, base64 |
| `KAROO_KEYSTORE_PASSWORD` | keystore password |
| `KAROO_KEY_ALIAS` | key alias |
| `KAROO_KEY_PASSWORD` | key password |

Create the key once (keep the file and passwords safe, never commit them):
```sh
keytool -genkeypair -v -keystore homewind-karoo.jks -alias homewind -keyalg RSA -keysize 4096 -validity 10000
base64 -i homewind-karoo.jks | pbcopy   # macOS: copies the value for KAROO_KEYSTORE_BASE64
```
Add the four secrets under Settings → Secrets and variables → Actions.

## License

Homewind License (PU + CU): free for private, non-commercial use; commercial use needs a separate license. See [LICENSE](LICENSE).

Third-party parts keep their own licenses: karoo-ext (Apache-2.0, built from source in CI), OkHttp, Jetpack Glance, Kotlin coroutines and the Gradle wrapper (all Apache-2.0).
