# On-device test checklist (0.5.3)

Everything in this release was checked with unit tests, lint and a build. None of it has run on a phone, so this list is the part only you can do. Each step says what to do, what you should see, and what a failure points to. If something fails, note the step number, your Android version and phone model, and what the screen showed.

Setup: install the APK, finish the setup wizard, and in Developer options choose GhostRoute as the **mock location app**. Use a second app that shows your location (any maps app) to see what other apps receive.

## 1. Basics

1. **Go.** Long-press a point on the map, press **Go**. *Expect:* a notification appears, the maps app shows that place within a few seconds. *Failure:* nothing moves → mock location app not selected (Diagnostics says so).
2. **Pause and Resume.** Start a route (step 3), press **Pause** next to Stop. *Expect:* the chip turns amber "paused", the location stops. **Resume** continues from the same spot.
3. **Stop** from the panel, the notification and the tile each end the run.

## 2. Route from a standing start (fixed in 0.5.2)

1. With nothing running, open **More tools → Route Studio**, add three waypoints a few hundred metres apart, set the speed to Walk, press **Run route**.
2. *Expect:* the dot starts at waypoint 1 and **moves** toward waypoint 2 and 3. *Failure:* it stays at waypoint 1 → the route extras are being ignored again (the 0.5.0 and 0.5.1 bug).

## 3. Follow roads

1. In Route Studio switch **Follow roads** on, tap **Show road route**. *Expect:* a blue line along real roads (not a straight line) and "Road route ready: … km".
2. Press **Run route**. *Expect:* the device follows the road line.
3. Put a waypoint in the sea or in the middle of a field far from roads. *Expect:* a clear message that a waypoint is more than 500 m from a road, and nothing starts.
4. Turn on airplane mode, press **Run route**. *Expect:* a "could not reach the routing service" message and nothing starts (no straight-line fallback).

## 4. Live with IP

Use a VPN you can switch between countries.

1. VPN on, country A. Tap the **live with IP** chip. *Expect:* chip "on", the app goes to country A's place and starts a run there.
2. Switch the VPN to country B (or disconnect and reconnect). *Expect:* within about 5 seconds the location moves to B. *Failure:* it only moves after minutes → the network change was not detected (the periodic check caught it).
3. Disconnect the VPN and **leave it off**. *Expect:* the chip says **waiting for VPN**, and the location **stays** at the last VPN place (it does not jump to where you really are).
4. Reconnect the VPN. *Expect:* the chip returns to "on" and the location follows the VPN's exit.
5. Turn the chip off and on while the VPN is off. *Expect:* now it follows your real IP location.
6. With the VPN excluded for GhostRoute (split tunnelling), turn the chip on. *Expect:* a banner saying the app bypasses the VPN, and the location does **not** move to your real place.
7. While the VPN switches, the **online** chip may flicker to "offline". *Expect:* it returns to "online" by itself within a few seconds of the new connection (it used to stay offline).

## 5. Things the audits flagged that need a phone

1. **Go without permission.** In Developer options choose a different mock location app (or none), then press **Go** in GhostRoute. *Expect:* an amber banner "Could not start: mock location is not set up" that says what to change and has a **Diagnostics** button; the app stays open. *Failure:* the app closes → the start-foreground fix did not hold; nothing appears → the refusal was not received (tell me which Android version).
2. **Rotation.** Start a run, long-press the app icon, tap the **Stop** shortcut, start a new run, then rotate the phone. *Expect:* the new run keeps going.
3. **Screen off.** Start a walking route, lock the screen for 5 minutes, watch the location in the other app. *Expect:* it keeps moving smoothly. *Failure:* it freezes and jumps when you unlock → the phone is pausing the app's timer. Tell me; a wake lock would fix it at some battery cost, and that is your call.
4. **Quick-settings tile.** Edit the tiles and add GhostRoute. *Expect:* a tile with a pin icon and the app name that starts and stops the last run.
5. **Home-screen widget.** Add the widget. *Expect:* it shows on/off and the button starts and stops the last run.

## 6. Look and feel

1. The map is **Liberty** (bright, colourful streets) on a fresh install. Settings → map style can switch to dark or light.
2. Switch the phone to Persian and open the map, Route Studio and Profiles. *Expect:* right-to-left layout with no overlapping buttons.
3. **Profiles:** choose a place, tap **Save current setup**, name it. *Expect:* a card with a summary and one **Start profile** button.

## 7. Backup

Export a backup from Settings, uninstall nothing, import it again. *Expect:* spots and routes are not duplicated; the setup wizard does not appear again.
