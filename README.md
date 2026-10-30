# GhostRoute

Privacy-first mock-location app for Android: pick any point — on the map, via `geo:` links, or by IP/VPN exit — and a foreground service feeds realistic synthetic GPS to the system through Android's test-provider API. No root, no Xposed; it registers as the device's *mock location app*, so other apps may detect the Android mock flag. No anti-detection is promised. Maps, search, opt-in IP lookups and opt-in road routing contact their disclosed providers.

## Screenshots

![GhostRoute main map screen, v0.5.3](docs/screenshots/app-0.5.3-main.jpg)

## Version 0.5.3 — Live with IP follows the VPN only, offline chip, visible start errors

- **Live with IP follows the VPN exit, never your real address.** Once a lookup of the session has ridden a VPN, losing the VPN holds the last location (the chip says "waiting for VPN") until a VPN carries the lookups again. Turning Live with IP off and on again follows whatever connection you have then. With no VPN involved it still follows network changes. Turning it on while GhostRoute bypasses the VPN (split tunnelling) no longer adopts your real location.
- **The online chip follows the phone's connectivity.** It used to stay "offline" after a failed map load (typically while a VPN switched) until the map restyled.
- **A refused start is shown.** When Android does not accept GhostRoute as the mock location app, a banner now says what to change and opens Diagnostics. Before, Go silently did nothing and the explanation was only in Diagnostics.
- [docs/DEVICE-TEST-CHECKLIST.md](docs/DEVICE-TEST-CHECKLIST.md) lists the checks only a phone can do.

## Version 0.5.2 — route start fix, safer starts, Live with IP guards

- **Starting a route from Route Studio while nothing was running did not run the route.** It started a *stationary* mock at the first waypoint; only when a simulation was already going did the route move the device. This was already the case in the code you uploaded, and it made Follow roads (0.5.0 and 0.5.1) useless from an idle start. The service now honours the route extras on START. New tests feed the exact intent Route Studio produces, including a Follow roads route, into the real service and check that the device travels along it; they fail against the old behaviour.
- **A start that is rejected no longer risks crashing the app.** Android crashes an app whose service is started with `startForegroundService()` and then stops before calling `startForeground()`, which is what happened when you tapped Go after GhostRoute stopped being the selected mock location app. The service now enters the foreground first and leaves again if nothing runs. This follows Android's documented rule; it could not be reproduced here without a device.
- The launch intent (Stop shortcut, `geo:` link) is no longer replayed when the screen rotates; the widget is refreshed when the state changes instead of every second; the quick-settings tile has a label and icon.
- Live with IP safeguards (details in the CHANGELOG): it never restarts a route run, ignores IPv6-only answers and lookups that bypass the VPN, applies a location that arrived while paused on resume, and defaults to a 5-minute periodic check.
- The intent table below was corrected: route mode names, what `extra-speed` does, what `UPDATE` does on a route run, and that `PING` replies are not visible to other apps.

## Version 0.5.0 — roads, Live with IP, simpler profiles

- **Follow roads** (Route Studio, off by default). With it on, a route's waypoints are snapped to real roads and the device moves along them instead of in straight lines over buildings and rivers. Walking pace (up to 9 km/h) uses footpaths, cycling pace (up to 25 km/h) bike routes, anything faster roads for cars. Use **Show road route** to preview the line on the map; Start and Apply use it, and so do route profiles. It asks the public routing service routing.openstreetmap.de (OpenStreetMap data, no key) when you preview or start, and sends nothing while the switch is off. Limits, all enforced and tested: a waypoint more than 500 m from a suitable road is rejected (the server would otherwise move it to a distant road), more than 100 waypoints is rejected, and a long route is simplified to fit the engine's 2,000-point limit (the screen shows the tolerance). If the service cannot be reached or finds no route, nothing starts and there is no straight-line fallback. The public server has no uptime guarantee.
- **Live with IP** (the chip formerly labelled "ip sync"). Turn it on and GhostRoute looks up your public IP, goes to the place it points to (for example your VPN's exit country) and starts there. While a simulation runs, a network change (VPN reconnect, server switch, Wi-Fi/cellular switch) triggers a new check within about two seconds, with retries while the network settles, and the location follows. If the VPN drops, it keeps the last location and waits for a VPN to return (0.5.3; versions 0.5.0 to 0.5.2 followed the direct address after 20 seconds). A VPN that changes server without reconnecting is only caught by the periodic check (Settings → refresh interval, 5 minutes by default; the public IP service has a free-tier limit, so the default is deliberately gentle). It follows only while a simulation is running, because Android does not let an idle app start one from the background; choosing a place on the map, starting a profile, or moving the joystick turns it off. Safeguards (0.5.2): it never restarts a route run, never follows an IPv6-only answer (a VPN tunnel is usually IPv4-only, so that answer comes from your real connection), never follows a lookup that bypasses the VPN, and a location that arrives while a run is paused is applied when it resumes. The VPN status line needed the network-state permission, which the manifest lacked; it is declared now.
- **Profiles are simpler**: "Save current setup" stores the selected place or running route in one step (name it and done); each profile has a single Start button and a plain summary; setting it up on the map without starting, editing, duplicating and deleting live in the menu; the interval and accuracy fields are gone from the editor (taken from your settings).
- **Liberty is the default map style.** Dark and light remain in Settings; anyone who explicitly chose one keeps it.

Nothing here was run on a device or emulator. Read [the 0.5.0 feature check](docs/FEATURE-VERIFICATION-0.5.0.md) for what was traced in code and what remains untested.

## Version 0.3.1 — Night Navigation

A more colorful, cohesive interface: periwinkle/blue/mint over layered navy, a redesigned destination dock, fixed Go/Stop control, icon navigation, grouped tools, clearer profile cards and dashboard metrics. No new runtime dependency or external font/image service is used.

[Design notes and render-test scope](docs/DESIGN-0.3.1.md) describe the changes. The screenshots in docs/design-previews are actual Compose component fixtures with sample data, not a live MapLibre/device test. All 0.3.0 features and runtime limitations below remain relevant.

## Version 0.3.0 — feature and reliability pass

Profiles now support Prepare and Start profile; routes retain the complete configuration on Go. Route Studio adds undo/redo, reverse, speed presets, Ping-pong and a usable map-picking workflow. More tools includes live destination apply, recenter to the simulated position and an auto-stop timer. Reliability fixes span joystick touch release, tile state, timeline refresh, search, backup and process-lifetime IP sync.

Read [the feature verification report](docs/FEATURE-VERIFICATION-0.3.0.md) before testing. Unit tests and a successful build are not device certification. Pause and resume (new in 0.4.0) hold the simulated position in place. Route seek, anti-detection, offline map packs and KML are not included. Backup recovery is best-effort across SQLite and DataStore.

## Project layout

- `app` — Compose UI (map, wizard, spots sheet), `MockService` foreground service, tile + widget, intent boundary.
- `engine` — pure-Kotlin simulation core: movement strategies (Static/Jitter/Route), signal realizer, coordinate parsing. No Android.
- `core-data` — persistence (Room spots/folders/recents + DataStore settings), geocoding (Photon→Nominatim), IP/VPN location sync.

## Build

Requires JDK 21 and the Android SDK with platform 36. Point `local.properties` (`sdk.dir=...`) at your SDK, then:

```sh
./gradlew assembleDebug      # installable debug build
./gradlew assembleRelease    # uses your local release key, or debug-signs if absent
./gradlew test               # full unit-test suite (all modules, both variants)
```

Release signing material is local-only. This source bundle omits `keystore.properties`
and `keystore/release.pks`; supply your own private key and local properties if
needed. Android only accepts an update signed by the same key, so treat signing
material included in the original uploaded archive as exposed and plan rotation
before public distribution.

## Install & first run

Install the APK (`adb install app/build/outputs/apk/release/app-release.apk` or side-load it), then on first launch the in-app setup wizard walks you through:

1. Enable developer options on the device.
2. Select GhostRoute as the **mock location app** (Developer options → *Select mock location app* — the wizard's guide card explains this step; there is no public intent to open that picker directly).
3. Grant notifications.

After the wizard you land on the map. Pan and zoom to explore without changing your selected destination; press **Set center here** or long-press an exact point to stage a new one. Save a destination as a named Spot, copy or share its `geo:` link, then press Go to start the mock location. During a run, the destination card shows straight-line range and bearing from the simulated position. Spots can also be imported/exported as CSV (`name,latitude,longitude,note`); import previews the rows, adds them unfiled, and applies the whole file or none of it. Quick settings tile and home-screen widget mirror the engine state.

## Intent API quick reference

`app.ghostroute/.location.MockService` (foreground service) accepts externally-startable intents — the automation path (Tasker, adb `am startservice`):

| Action | Extras |
|---|---|
| `app.ghostroute.START` | `extra-simulator-config` (Parcelable) **or** `latitude` + `longitude` (string); optional `extra-interval-ms`, `extra-accuracy`, `extra-seed`, and `extra-speed` (only tunes the tick spacing of a stationary run; it does not make the device move). Add the route extras of `SET_ROUTE` to start a route run (0.5.2; earlier versions ignored them) |
| `app.ghostroute.STOP` | — |
| `app.ghostroute.UPDATE` | `latitude` + `longitude` (new anchor of a stationary run) and/or route extras. On a route run the anchor is ignored and the route restarts from its first point |
| `app.ghostroute.SET_ROUTE` | `extra-route-points` (`"lat,lng;lat,lng;…"`, 2–2,000 points), optional `extra-route-speed` (m/s), `extra-route-mode` (`loop` is the default, `once`, `pingpong`) |
| `app.ghostroute.PAUSE` | — holds the simulated position in place; samples keep flowing |
| `app.ghostroute.RESUME` | — continues from the paused spot |
| `app.ghostroute.PING` | — (broadcasts `app.ghostroute.ACTION_PONG` with a `state` extra, but only inside GhostRoute's own package; other apps cannot receive it) |

String extras are the baseline-compatible path; invalid or out-of-range values are rejected without touching the running state.

---

# GhostRoute (فارسی)

اپ موقعیت‌یابی جعلیِ مبتنی بر حریم خصوصی برای اندروید: هر نقطه‌ای را انتخاب کنید — روی نقشه، از طریق لینک‌های `geo:`، یا از روی IP خروجی VPN — و یک سرویس پیش‌زمینه GPS مصنوعی و واقع‌نما را از طریق API آزمایشی اندروید به سیستم تزریق می‌کند. بدون روت و بدون Xposed؛ اپ به‌عنوان *mock location app* دستگاه ثبت می‌شود، برنامه‌های دیگر ممکن است موقعیت آزمایشی را تشخیص دهند. نقشه، جستجو و استعلام اختیاری IP به سرویس‌های معرفی‌شده وصل می‌شوند.

## ساختار پروژه

- `app` — رابط Compose (نقشه، تنظیمات اولیه، شیت مکان‌ها)، سرویس پیش‌زمینه `MockService`، کاشی و ویجت، مرز اینتنت.
- `engine` — هسته شبیه‌سازی کاتلین خالص: استراتژی‌های حرکت (Static/Jitter/Route)، تحلیل مختصات. بدون اندروید.
- `core-data` — ذخیره‌سازی (Room برای مکان‌ها/پوشه‌ها + DataStore برای تنظیمات)، جست‌وجوی مکان (Photon→Nominatim)، همگام‌سازی موقعیت IP/VPN.

## بیلد

به JDK 21 و Android SDK با پلتفرم ۳۶ نیاز دارید. مسیر SDK را در `local.properties` (`sdk.dir=...`) تنظیم کنید و سپس:

```sh
./gradlew assembleDebug      # بیلد اشکال‌زدایی قابل نصب
./gradlew assembleRelease    # از کلید محلی شما استفاده می‌کند؛ در نبود آن با کلید debug امضا می‌شود
./gradlew test               # کل مجموعه تست‌ها (همه ماژول‌ها، هر دو واریانت)
```

اطلاعات امضای نسخهٔ انتشار باید فقط روی دستگاه خودتان بماند. این بستهٔ منبع،
`keystore.properties` و `keystore/release.pks` را شامل نمی‌شود؛ در صورت نیاز،
کلید خصوصی و فایل تنظیمات محلی خودتان را فراهم کنید. اندروید فقط نسخه‌ای را
به‌روزرسانی می‌کند که با همان کلید امضا شده باشد؛ بنابراین اطلاعات امضای موجود
در آرشیو اصلیِ ارسالی را افشاشده فرض کنید و پیش از انتشار عمومی برای تعویض آن
برنامه‌ریزی کنید.

## نصب و اجرای اول

APK را نصب کنید (`adb install app/build/outputs/apk/release/app-release.apk` یا انتقال دستی)، سپس در اجرای اول جادوگر داخل اپ شما را قدم‌به‌قدم راهنمایی می‌کند:

1. گزینه‌های توسعه‌دهنده را روی دستگاه فعال کنید.
2. GhostRoute را به‌عنوان **mock location app** انتخاب کنید (Developer options → *Select mock location app* — کارت راهنمای جادوگر این مرحله را توضیح می‌دهد؛ اینتنت عمومی برای باز کردن آن انتخاب‌گر وجود ندارد).
3. اجازه اعلان‌ها را بدهید.

پس از راه‌اندازی به نقشه می‌رسید. برای گشت‌وگذار نقشه را جابه‌جا یا بزرگ‌نمایی کنید؛ این کار مقصد انتخاب‌شده را تغییر نمی‌دهد. برای ثبت مقصد جدید «انتخاب مرکز» را بزنید یا نقطهٔ دقیق را لمس طولانی کنید. مقصد را به‌عنوان مکان نام‌دار ذخیره کنید، مختصات یا پیوند `geo:` آن را کپی/هم‌رسانی کنید و سپس برای شروع شبیه‌سازی «شروع» را بزنید. هنگام اجرا، کارت مقصد فاصلهٔ مستقیم و جهت را از موقعیت شبیه‌سازی‌شده نشان می‌دهد. مکان‌های ذخیره‌شده را با CSV شامل ستون‌های `name,latitude,longitude,note` درون‌ریزی یا برون‌بری کنید؛ پیش‌نمایش نمایش داده می‌شود و مکان‌های درون‌ریزی‌شده بدون پوشه می‌مانند؛ کل فایل اعمال می‌شود یا هیچ‌چیز. کاشی تنظیمات سریع و ویجت صفحهٔ اصلی وضعیت موتور را نشان می‌دهند.

## مرجع سریع API اینتنت

`app.ghostroute/.location.MockService` (سرویس پیش‌زمینه) اینتنت‌های بیرونی می‌پذیرد — مسیر اتوماسیون (Tasker یا `am startservice` از adb):

| اکشن | اکستراها |
|---|---|
| `app.ghostroute.START` | `extra-simulator-config` (Parcelable) **یا** `latitude` + `longitude` (رشته)؛ اختیاری: `extra-interval-ms`، `extra-accuracy`، `extra-seed` و `extra-speed` (فقط فاصلهٔ تیک‌ها را تنظیم می‌کند و دستگاه را حرکت نمی‌دهد). برای شروع اجرای مسیر، اکستراهای مسیرِ `SET_ROUTE` را اضافه کنید (از نسخهٔ ۰٫۵٫۲؛ نسخه‌های قبل آن‌ها را نادیده می‌گرفتند) |
| `app.ghostroute.STOP` | — |
| `app.ghostroute.UPDATE` | `latitude` + `longitude` (لنگر جدیدِ اجرای ثابت) و/یا اکستراهای مسیر. در اجرای مسیر، لنگر نادیده گرفته می‌شود و مسیر از نقطهٔ اول دوباره شروع می‌شود |
| `app.ghostroute.SET_ROUTE` | `extra-route-points` (`"lat,lng;lat,lng;…"`، ۲ تا ۲٬۰۰۰ نقطه)، اختیاری: `extra-route-speed` (متر بر ثانیه)، `extra-route-mode` (`loop` پیش‌فرض است، `once`، `pingpong`) |
| `app.ghostroute.PAUSE` | — موقعیت شبیه‌سازی‌شده را ثابت نگه می‌دارد |
| `app.ghostroute.RESUME` | — از همان نقطهٔ مکث ادامه می‌دهد |
| `app.ghostroute.PING` | — (یک broadcast با `app.ghostroute.ACTION_PONG` و اکسترای `state` می‌فرستد، اما فقط داخل بستهٔ خود GhostRoute؛ برنامه‌های دیگر آن را دریافت نمی‌کنند) |

اکستراهای رشته‌ای مسیر سازگاری پایه هستند؛ مقادیر نامعتبر یا خارج از محدوده بدون دست‌زدن به وضعیت در حال اجرا رد می‌شوند.
