# Homewind Bike Computer

Bike computer integrations for [Homewind](https://github.com/mLihs/Homewind): show Homewind's fan state on the bike computer and change fan speed and mode from it.

| Folder | Device | Status |
| --- | --- | --- |
| [`karoo3/`](karoo3/) | Hammerhead Karoo 3 (karoo-ext extension) | 1.0.0, Homewind 2.2 state API (2.0/2.1 over WebSocket) |

Planned: Garmin (Connect IQ).

## Updates on the Karoo

The app declares an update manifest (`io.hammerhead.karooext.MANIFEST_URL`): `https://github.com/mLihs/Homewind-Bike-Computer/releases/download/karoo3-latest/manifest.json`. Every signed release rewrites it (release `karoo3-latest`, a fixed channel so other releases in this repo do not interfere). The Karoo compares `latestVersionCode` with the installed `versionCode` and offers the update in its Extension Library.

## Builds and releases

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
