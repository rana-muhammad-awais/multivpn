# MultiVPN — self-contained multi-country VPN for Android

A fully independent Android VPN app (Kotlin). The OpenVPN engine is **compiled into the APK** — no companion apps, no accounts, nothing else to install. Server coverage comes from the public [VPN Gate](https://www.vpngate.net) academic project and includes **every country that currently has volunteer relays** (typically 10–20 countries at any moment — Japan, Korea, US, Thailand, Vietnam, Russia, India, Indonesia and more; the list changes hourly and the app always shows all of it).

## How it works

- On launch the app downloads the live VPN Gate relay list and shows all servers grouped by country, with live ping / speed / user stats. The header shows how many servers and countries are online right now.
- Tap a country to expand its servers, tap one to connect — or hit **Fastest** for the best-scored server in that country.
- The encrypted tunnel runs on the embedded [ics-openvpn](https://github.com/schwabe/ics-openvpn) core — the same open-source engine trusted by millions — living inside this app's own process and shipping inside the APK.
- The server list is cached for instant, offline startup; pull down to refresh.

## How the engine is embedded (v3, from source)

There is **no second app to install**. The OpenVPN engine (schwabe/ics-openvpn,
pinned to tag `v0.7.51`) is compiled *from source* and linked directly into this
APK by the GitHub Actions workflow. The workflow:

1. Clones the engine repo **with its native C submodules** (OpenVPN + OpenSSL).
2. Installs the Android NDK and CMake.
3. Includes the engine's `:main` module as a local Gradle module (`:openvpn`).
4. Builds one self-contained APK containing the native `.so` tunnel libraries.

No JitPack, no prebuilt jar, no external dependency at runtime. One app, one icon.

## Build

**Cloud (no tools needed):** push this folder to a GitHub repository → the included workflow (`.github/workflows/build-apk.yml`) builds automatically → download the `MultiVPN-debug` artifact from the Actions tab → `app-debug.apk` is inside.

**Local:** open the folder in Android Studio → Run ▶. (If asked about a missing Gradle wrapper, accept the default.)

## First connection

1. Pick a country → tap a server or **Fastest** → **Connect**.
2. Android shows its one-time system VPN consent dialog → **OK**.
3. Status card: yellow = connecting, green = connected. A key icon appears in the status bar. **Disconnect** stops the tunnel.

## Notes

- **Stability:** relays are volunteer-run; if one stalls, disconnect and pick the next (the list is sorted best-first). The tunnel service itself runs as a foreground service and survives the app being closed.
- **targetSdk is 30** on purpose: the embedded engine's manifest predates Android 12's `android:exported` rule. This only matters for Play Store publishing, not for direct installs.
- **License:** the embedded engine is GPLv2. For personal use this changes nothing; if you distribute the app publicly you must make your source code available under the GPL as well.
- **Privacy:** VPN Gate is an anti-censorship research project; relay operators can see traffic metadata and the project keeps connection logs ~3 months for abuse prevention. Prefer HTTPS sites for sensitive activity.
- **Rebranding:** change `app_name` in `app/src/main/res/values/strings.xml`, the `applicationId` in `app/build.gradle`, and the icon at `app/src/main/res/drawable/ic_launcher.xml`.

## Project layout

```
app/src/main/java/com/multivpn/app/
  MainActivity.kt        UI, VPN consent flow, connect/disconnect
  VpnEngine.kt           Wrapper around the embedded OpenVPN core
  VpnGateApi.kt          VPN Gate API client + parser + country grouping
  ServerListAdapter.kt   Expandable country/server list
  App.kt                 Notification channels for the tunnel service
```
