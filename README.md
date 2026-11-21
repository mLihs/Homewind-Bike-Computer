# Homewind Bike Computer

Bike computer integrations for [Homewind](https://github.com/mLihs/Homewind): show Homewind's fan state on the bike computer and change fan speed and mode from it.

| Folder | Device | Status |
| --- | --- | --- |
| [`karoo3/`](karoo3/) | Hammerhead Karoo 3 (karoo-ext extension) | MVP 0.1.0, works with Homewind 2.0 |

Planned: Garmin (Connect IQ).

## Builds

GitHub Actions builds the Karoo APK on every push (artifact `homewind-karoo3-apk`). A tag `karoo3-v<version>` (e.g. `karoo3-v0.1.0`) also attaches it to a GitHub release.

## License

Homewind License (PU + CU): free for private, non-commercial use; commercial use needs a separate license. See [LICENSE](LICENSE).

Third-party parts keep their own licenses: karoo-ext (Apache-2.0, built from source in CI), OkHttp, Jetpack Glance, Kotlin coroutines and the Gradle wrapper (all Apache-2.0).
