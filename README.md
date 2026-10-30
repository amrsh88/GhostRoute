<div align="center">

<img src="docs/assets/logo.png" alt="GhostRoute logo" width="160"/>

# GhostRoute

**Privacy-first mock-location app for Android.**

Pick any point — on the map, via `geo:` links, or by IP/VPN exit — and a foreground service feeds realistic synthetic GPS to the system through Android's test-provider API.

[![Release](https://img.shields.io/github/v/release/amrsh88/GhostRoute?style=flat-square)](https://github.com/amrsh88/GhostRoute/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/amrsh88/GhostRoute/ci.yml?branch=main&style=flat-square)](https://github.com/amrsh88/GhostRoute/actions)
[![Tests](https://img.shields.io/badge/tests-1138%20passing-2ea44f?style=flat-square)](https://github.com/amrsh88/GhostRoute/actions)
[![License](https://img.shields.io/github/license/amrsh88/GhostRoute?style=flat-square)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%207.0%2B%20(API%2026%2B)-3465a4?style=flat-square)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square)
![No tracking](https://img.shields.io/badge/tracking-none-2ea44f?style=flat-square)

*[فارسی](#فارسی) · [Download](#download) · [Intent API](#intent-api)*

</div>

---

## Why GhostRoute

Most mock-location tools are ad-laden, closed-source, or ask for a bajillion permissions. GhostRoute is the opposite:

- **No root, no Xposed** — it registers as the device's *mock location app* (Developer options), nothing more.
- **Keyless by design** — no API keys, no accounts, no analytics, no crash reporting. If the app has no Internet-permission reason to know who you are, it doesn't.
- **Honest by design** — it does *not* promise to defeat mock-location detection. Other apps can see the Android "mock" flag. There is no stealth rewrite of system semantics, and the README will never claim otherwise.

## Features

| | |
|---|---|
| 🗺️ **Map-first single window** | Pan, long-press or *Set center here* to stage a destination — built on MapLibre with the keyless OpenFreeMap styles (night-themed default). |
| 🌐 **Live with IP** | Start where your public IP points — typically your VPN's exit country — and follow VPN server changes live while a run is active. VPN-aware guards: never follows IPv6-only or VPN-bypassed lookups, never adopts your real address when split-tunnelling lets a lookup slip past the tunnel. |
| 🛣️ **Follow roads** | Snap routes to real streets via the public OSRM service: footpaths for walking, bike routes for cycling, roads for driving. |
| 🎛️ **Route Studio** | Waypoint routes with undo/redo, reverse, speed presets, loop / once / ping-pong movement, and map picking. |
| 🎮 **Floating joystick** | Opt-in on-map joystick pad for real-time nudging (drawn by the foreground service, never without permission). |
| ⭐ **Spots & folders** | Save named destinations, import/export CSV, one-tap `geo:` copy/share. |
| 📡 **Automation** | Full intent API — start/stop/update/pause/route from Tasker, adb, or any automation app. |
| 🔔 **Tile & widget** | Quick-settings tile and home-screen widget mirror engine state. |
| 🌓 **Persian UI** | Full RTL support, English + فارسی. |

## Download

Grab the signed APK from [**Releases**](https://github.com/amrsh88/GhostRoute/releases).

> Upgrading from an install signed with a different key? Android refuses same-package updates across signatures — uninstall first, then install.

## Install & first run

GhostRoute uses Android's official mock-location facility, so first launch walks you through a 3-step wizard:

1. Enable **Developer options**.
2. Developer options → **Select mock location app** → GhostRoute.
3. Grant notifications.

That's it — you land on the map. Long-press a point or use **Set center here**, save it as a Spot if you like, press **Go**, and the synthetic GPS stream starts. A destination card shows live range and bearing from the simulated position.

> ⚠️ **Detection honesty:** GhostRoute is the device's registered *mock location app*, so apps that check `Location.isMock()` (and tools like Play Integrity) can tell the position is simulated. GhostRoute does not hide this and no bypass is planned.

## Screenshots

| Main map | Destination dock | Live with IP |
|---|---|---|
| ![Main map screen](docs/screenshots/app-0.5.3-main.jpg) | ![Destination dock](docs/design-previews/destination-dock.png) | ![Live with IP chip](docs/design-previews/live-ip-chip.png) |

More component previews live in [`docs/design-previews/`](docs/design-previews) — these are real Compose fixtures rendered with sample data.

## Build from source

Requires **JDK 21** and the **Android SDK (platform 36)**.

```sh
git clone https://github.com/amrsh88/GhostRoute.git
cd GhostRoute
./gradlew assembleDebug      # installable debug build
./gradlew assembleRelease    # signed with your local key (keystore.properties), or debug-signed
./gradlew test               # full unit-test suite — 1,138 tests
```

Release signing material is intentionally **not** in this repository. Create your own `keystore.properties` at the repo root:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Privacy

- **No telemetry, no analytics, no crash reporting, no Firebase.** The manifest asks for exactly what the features need, and nothing phones home.
- Network contact, fully disclosed and keyless: OpenFreeMap (map tiles/styles), Photon→Nominatim (place search), OSRM's public instance (road routing, only when *Follow roads* is on), and an IP-lookup service (only when *Live with IP* is on).
- Spots, profiles and settings live in a local Room database + DataStore. CSV/backup export is a plain local file.

See [`docs/intents.md`](docs/intents.md) for the exact network endpoints and [`docs/DEVICE-TEST-CHECKLIST.md`](docs/DEVICE-TEST-CHECKLIST.md) for on-device verification steps.

## Project layout

```
app/        Compose UI (map, wizard, spots sheet), MockService foreground
            service, quick-settings tile + widget, intent boundary
engine/     Pure-Kotlin simulation core: movement strategies
            (Static/Jitter/Route), signal realizer, coordinate parsing.
            Zero android.* imports.
core-data/  Room persistence (spots/folders/recents) + DataStore settings,
            geocoding, IP/VPN location sync, backup
```

## Intent API quick reference

`app.ghostroute/.location.MockService` accepts externally-startable intents — the automation path (Tasker, `adb shell am startservice`):

| Action | Extras |
|---|---|
| `app.ghostroute.START` | `extra-simulator-config` (Parcelable) **or** `latitude` + `longitude` (string); optional `extra-interval-ms`, `extra-accuracy`, `extra-seed`, `extra-speed`. Add the `SET_ROUTE` extras to start a route run. |
| `app.ghostroute.STOP` | — |
| `app.ghostroute.UPDATE` | `latitude` + `longitude` (new anchor of a stationary run) and/or route extras. |
| `app.ghostroute.SET_ROUTE` | `extra-route-points` (`"lat,lng;lat,lng;…"`, 2–2,000 points), optional `extra-route-speed` (m/s), `extra-route-mode` (`loop`*default*, `once`, `pingpong`). |
| `app.ghostroute.PAUSE` | — holds the simulated position in place. |
| `app.ghostroute.RESUME` | — continues from the paused spot. |
| `app.ghostroute.PING` | — broadcasts `app.ghostroute.ACTION_PONG` (own package only). |

String extras are the baseline-compatible path; invalid or out-of-range values are rejected without touching a running session.

## Contributing

PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). The short version: tests first, no new dependencies, no telemetry, keep `:engine` pure Kotlin.

## License

[GPL-3.0](LICENSE) © Amirreza

---

<div align="center">

## فارسی

<img src="docs/assets/logo.png" alt="لوگوی GhostRoute" width="96"/>

**موقعیت‌یاب جعلیِ مبتنی بر حریم خصوصی برای اندروید** — هر نقطه‌ای را انتخاب کنید: روی نقشه، با لینک `geo:`، یا از روی IP خروجی VPN؛ یک سرویس پیش‌زمینه، GPS مصنوعیِ واقع‌نما را از طریق API آزمایشی اندروید سیستم تزریق می‌کند.

- **بدون روت، بدون Xposed** — فقط ثبت به‌عنوان *mock location app* در گزینه‌های توسعه‌دهنده
- **بدون کلید و بدون ردیابی** — نقشه OpenFreeMap، جست‌وجوی Photon/Nominatim، مسیریابی OSRM؛ همه بدون کلید
- **صداقت کامل** — برنامه‌های دیگر می‌توانند پرچم موقعیت آزمایشی اندروید را ببینند؛ هیچ راه‌دور زدنی وعده داده نمی‌شود

**نصب:** APK را از [Releases](https://github.com/amrsh88/GhostRoute/releases) بگیرید، سپس در اجرای اول جادوگر سه مرحله‌ای (گزینه‌های توسعه‌دهنده → انتخاب mock location app → اجازه اعلان‌ها) را کامل کنید.

**بیلد:** به JDK 21 و Android SDK (پلتفرم ۳۶) نیاز دارید:

```sh
./gradlew assembleDebug    # بیلد اشکال‌زدایی
./gradlew test             # ۱۱۳۸ تست
```

مستندات کامل اینتنت و نقاط پایانی شبکه در [`docs/intents.md`](docs/intents.md).

</div>
