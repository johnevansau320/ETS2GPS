# ETS2 GPS

Turn-by-turn GPS for Euro Truck Simulator 2 on an Android phone, using the **map you actually have installed**
(base game, DLC and map mods such as ProMods), with voice prompts and maneuver graphics on a heading-up road map.

```
 ETS2 + TruckSim GPS plugin â”€â”€â–º TruckSim GPS Telemetry Server (:31377) â”€â”€â–º phone app   (live truck position)
 ETS2 game files + active mods â”€â–º companion/ (Python) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º phone app   (road map, once, :31378)
```

* **Telemetry** (position, heading, speed, speed limit, job destination) comes from the
  [TruckSim GPS telemetry server](https://github.com/TruckSim-GPS/trucksim-gps-server) you already run.
* **Map data** is extracted by `companion/` straight from the `.scs` archives (HashFS v1/v2 and zip/folder mods),
  in the same load order as your active profile (it asks the telemetry server for the profile's mod list).
  Output is one compact file, `etsmap.bin` (about 15 MB for base game + ProMods).
* **The app** downloads that file once, then routes (A* over the real road graph including junction paths),
  draws roads/route, generates maneuvers and speaks them with Android text-to-speech.

## 1. PC side

Python 3.9+ (no extra packages). With the TruckSim GPS telemetry server installed and your game closed or open:

```
cd companion
start_companion.bat            (or: python companion.py)
```

First run builds the map (~30 s) and then serves it on port **31378**. It rebuilds automatically when your game
files or active mod list change. Options: `--no-mods`, `--rebuild`, `--game-dir "D:\...\Euro Truck Simulator 2"`.

**Start automatically with Windows:** right-click `companion\install_autostart.ps1` > *Run with PowerShell*. It creates a
scheduled task that starts the companion hidden 20 seconds after you log in (output goes to `companion\companion.log`).
Undo with `remove_autostart.ps1`. If you move this folder, run the install script again.

Allow the port through Windows Firewall once (admin prompt):

```
netsh advfirewall firewall add rule name="ETS2 GPS companion" dir=in action=allow protocol=TCP localport=31378
```

## 2. Phone side

A ready-built debug APK is included as `ets2-gps-debug.apk` (sideload it; allow "install unknown apps"). To rebuild it, use one of:

* **Android Studio**: open the `android/` folder, let it sync, Run (JDK 17, compileSdk 34).
* **Command line**: `gradle -p android assembleDebug` (Gradle 8.7, JDK 17, Android SDK 34).
* **GitHub Actions**: push this folder to a repo; `.github/workflows/android.yml` builds and uploads the APK.

Then in the app: gear icon -> enter your PC's LAN IP -> **Save + download map**. Start the game; the truck appears.
Choose a destination with **Search**, **Job** (current job's destination), or long-press anywhere on the map.
Pinch / +/- to zoom, double-tap resets zoom.

## Extras

- **Update check** (v1.11.1): the Android app looks for a newer release on GitHub about once a day (**Settings > Updates**: open the download page, skip a version, check now, or switch it off). Nothing is downloaded or installed; it only opens the release page. The Windows app and the web version have the same notice.
- **Better roundabouts and exits** (v1.11.0): roundabouts built from several pieces or not flagged by the map builder are announced as roundabouts; looping ramps say "take the exit on the right / left"; delivered or cancelled jobs end the route; prompts are not repeated after a recalculation and nothing is recalculated inside a yard.
- **Job routes lead you to the gate** (v1.10.0): when the mapped route ends short of the company the app keeps the route on the map, says once to follow the flag to the drop-off point and shows the game's remaining distance; a chequered flag marks the destination. *Recalculating* is spoken once per time off the route, and lane guidance can be switched off in Settings (Windows and Android).

- **Lane guidance**: while you drive the app shows, for the next junction that has lane data (turns, exits and straight-on junctions where some lanes would leave your route), one arrow per lane of your road (left to right) under the green bar. Green lanes are the ones to be in for your turn, white lanes show where they go, and a faint second arrow marks a lane that serves two directions. The lane data comes from the game's junction prefabs, so the map has to be rebuilt once (the companion does this automatically after you update it) and re-downloaded in the app.
- **Saved trips**: after planning a route (and any waypoints) tap **Trips > Save current trip** and give it a name. Saved trips keep the destination, waypoints and route type; tap one later to start it from wherever you are, rename it or delete it.
- **Waypoints**: once a route exists, search a city and choose *Add as waypoint*, long-press the map and choose *Add as waypoint*, or open **Stops** (Search > Stops) to add, remove, reorder or clear them. Waypoints show as numbered blue markers; the app guides you to each in turn ("Waypoint reached") and the distance/ETA cover the whole trip.
* **POIs** (from the game's own prefab spawn points and map overlays): fuel stations, service centres, rest areas /
  parking, weigh stations, garages, truck dealers. Shown as pictogram badges on the map; the **POI** button lists the
  nearest ones (things behind you rank lower) and routes there, then resumes your original destination on arrival.
* **Voice on your PC headset**: gear -> "Speak on the PC" (or "both"). The app sends each prompt to the companion,
  which speaks it with Windows' own voices (default: Microsoft Hazel/Zira) on the PC's default playback device. If your
  headset is not the default device, set it per app in Windows Settings > System > Sound > Volume mixer
  (choose the output for `powershell.exe`). Falls back to the tablet if the PC cannot be reached.
  Companion options: `--voice-rate -10..10`, `--voice "Microsoft Zira Desktop"`. The same endpoint can be tested with
  `http://<pc>:31378/say?text=Hello`.
* **Alerts**: low fuel and "rest needed in 30 / 10 minutes" are spoken with the nearest fuel station / rest area.
* **Towards**: turn prompts say which town the road heads for ("turn right towards Hamburg"); the status line shows
  "Near <town>".
* **Avoid ferries**: gear -> *Avoid ferries and car trains* keeps routes off every ferry and train link (the Channel crossing, for example); if a place can only be reached that way the bar says so. Saved trips remember it.
* **Road-atlas map**: roads are steel blue with a darker outline on a slate background. From a moderate zoom the map also shows the lots and yards around companies (tan) and green patches, a white-on-blue / green shield for each road number (E45, A7, SH 2 ...), and a name tag for every company (shown as in the game, e.g. DHL). Fuel, rest areas, service centres, garages, truck dealers, weigh stations and ferry / car-train terminals (B / T) are drawn by default. This needs the map built by the Windows app (map file version 9); an older map still works, without the extras.
* **3D view, logos, borders and flags**: above 15 km/h the map tilts into a perspective view (roads and labels shrink into the distance, with haze); it flattens again when you slow down below 12 km/h or look around. Company tags show the company's logo when the game has one. Border crossings are marked (a BORDER tag, and the flag of the country you are entering), and big upper-case area (city) names are drawn on the map. The logos and flags are taken from your own game files by the Windows app's map builder (`etsassets.bin`, about 1.3 MB, downloaded with the map); they are never part of the source code or a release.
* **Trip planner (no game, no job)**: *Plan* in the left menu (or *Plan a trip…* in the Trips list) plans a trip without the game running: choose a start, add stops, choose the destination (city search; while previewing, long-press the map to use any spot), then *Preview on map* (route, length and time, zoomable all the way out), *Save trip* and later *Drive this trip*. A saved trip remembers its start: *Drive this trip* first leads you to the start (unless you are already near it) and then through the stops; *Drive from here* skips the start. Trips can be reopened with *View / edit the plan*, renamed and deleted. Available in the Windows app, the web version and the Android app.
* **Share trips**: *Trips* -> *Import / export…* (and *Export this trip…* on a single trip) writes trips to a small `.ets2trip` file you can send to friends or post online, and reads one back in. The file is plain JSON and the same in the Windows app, the web version and the Android app. Trips are map positions, so they fit the same map (game and ProMods versions) they were made on; on import, duplicates are skipped, a name that is already used gets a `(2)`, and trips that are not valid or are outside the loaded map are skipped.
* **Route types** (gear -> Route type, or the quick button in the bottom bar that cycles Heavy / Fast / Tour):
  **Heavy transport** (default) strongly prefers motorways and major roads and avoids small roads and tight town
  junctions; **Fastest** is pure travel time; **Tourist** prefers smaller, scenic roads and avoids motorways. Road grades
  (motorway / expressway / wide local / minor) come from each road's lane types in the game data. Changing the mode
  re-plans the active route immediately.
* **Trip / job screen**: the blue **Job Info** button (top of the left column) swaps the map for a dashboard modelled on the
  SimHub "ETS2 overlay" design, drawn on a fixed 16:9 canvas that scales to any wide screen (the green and bottom bars are
  hidden while it is open). Header: FROM / route progress / TO, local and ETS2 clocks. Row 1: Cargo, Arrival (ETS2 clock,
  ETA on your clock, time left in game and real time), Time remaining to deadline (ON TIME / LATE, margin, average speed
  needed), Fuel for this trip (needed, at arrival, ENOUGH FUEL / REFUEL NEEDED) and Job & income (income rate, market).
  Row 2: this-trip statistics, trailer and cargo damage, truck, route and time and rest. On tall (portrait-like) screens
  it falls back to a scrollable card grid. **Reset trip** is in the header and also happens when a new job starts. The
  telemetry server reports trailer and cargo damage but not the truck's own damage.
* **Ferries / trains** are routed using the game's connection list and sailing times.
* **Road names**: ETS2's map files do not store road names or numbers (only sparse text on sign boards), so the app
  names the next town instead.
* **Flyovers and bridges**: the map now holds road heights. A road on a flyover, bridge or stacked interchange is drawn on top of the roads it crosses, with a shadow, and is raised in the 3D view while you drive; your route goes visibly over or under a crossing (the part below shows faintly through the deck). The map has to be rebuilt once (the Windows app does it itself when the game and the TruckSim server are running; on Android download the map again).
* **Border flags with the country name**: the flag of the country you are entering is drawn large with the country's name right under it.
* **Pictogram badges**: fuel (lime green), rest areas, service centres, weigh stations, garages, truck dealers, ferries and car trains are drawn as a coloured badge with a picture instead of a letter.
* **Left menu auto-hides**: after a few seconds without being touched the menu fades away and a thin grip stays on the left edge; touch the grip to bring it back.
* **Real or Sim distances**: Settings > *Real distances* switches turn distances, lane guidance and trip lengths between the game's scaled (Sim) distances and real metres. The scale itself (default 19) is unchanged. The **Job Info screen always counts in game kilometres**, because it compares your route with figures that come from the game (planned distance, range, fuel use).
* **Cruise control follows the speed limit** (on by default; menu button *Cruise auto / Cruise off*): while cruise control is on, the Windows app taps the game's cruise faster / slower keys (Numpad + and Numpad -, the game's defaults) to keep it on the current speed limit. It only presses keys while Euro Truck Simulator 2 is the window in front. The web and Android buttons switch it on or off on the PC, so they need the Windows app running.

## Web version (iPad, iPhone or any browser)

The same GPS also runs as a web app, served by the companion, so an iPad needs no App Store app and no Mac:

1. Update and restart the companion (re-run `install_autostart.ps1` if you use autostart). The console prints `web app: http://<pc-ip>:31378/`.
2. On the iPad (same Wi-Fi) open that address in Safari. First load downloads and unpacks the map (about 15 MB, a few seconds).
3. Share > **Add to Home Screen** for a full-screen app. Set Settings > Display > Auto-Lock to **Never** while driving.
4. Tap **Voice** once so the browser allows speech. In Settings you can speak on the iPad, on the PC headset (via the companion) or both.

It has the same routing, guidance, lane panel, waypoints, saved trips (stored in the browser), POI search, alerts and Trip screen as the Android app. Differences: the Trip screen is a card grid, and routing runs in the browser (a very long route can pause the map for a moment). It needs iPadOS / Safari 16.4 or newer (or a current Chrome, Edge or Firefox). The browser talks only to the companion, which forwards telemetry from the TruckSim GPS server on the PC.

## PC lane overlay

A small always-on-top window on the PC that shows the lane panel, like the Tachograph overlay. It is a .NET 8 WPF app (`overlay-dotnet`):

1. Build it once (needs the .NET 8 SDK): `overlay-dotnetuild.ps1`. This makes a single self-contained `publish\Ets2LaneOverlay.exe` (about 63 MB, nothing else to install).
2. `overlay-dotnet\install.ps1` copies it to `%LOCALAPPDATA%\Programs\ETS2 Lane Overlay`, adds a Start Menu shortcut and can start it with Windows. Or just run the exe.
3. Start the (updated) companion. If it runs on another PC, start the overlay with `--server http://<pc-ip>:31378` (or right-click > Server address).
4. Drive with the tablet app (or web app) as usual: whenever it shows the lane panel it sends it to the companion, and the overlay shows the same panel.

Controls: drag to move, drag the edges or corners to resize, mouse wheel to resize, **Ctrl + wheel** or right-click > Opacity slider for opacity, right-click > Size presets, Always on top, Hide when there is no lane data, Show demo lanes (to place it before you drive), Server address, Reset. A tray icon has the same menu. Position, size and opacity are remembered in `%APPDATA%\ETS2-GPS\lane_overlay_net.json`. ETS2 must run in windowed or borderless mode, otherwise no overlay can appear on top of the game.

`overlay\lane_overlay.py` is the earlier Python / PyQt6 version of the same overlay; it works the same way and can be used instead.

## Notes and limits

* ETS2's map is 1:19. The game's own GPS shows distances as map metres x 19, so the app does too by default
  (settings -> distance scale; set 1 for raw map metres). Voice prompts are timed by speed, not by distance.
* Roads are driven in the direction their lanes allow; junction turns come from each prefab's nav curves, so
  one-way ramps and slip roads are respected. 
* Roads flagged "hidden" in the map data are invisible in game, and the in-game GPS does not route over them (some
  are blocked by invisible barriers). They are only used when there is no sensible alternative (14x cost), because a
  few ProMods ramps depend on them. If you still meet a blocked road, long-press it on the map and choose
  **Avoid road here**; it is remembered (gear -> Clear avoided roads to undo) and the route is re-planned.
* Roundabouts are recognised from the lane geometry of each *placed* junction, not from names: the prefab must
  contain a ring (curved lanes sharing one circle, or a closed lane loop), at least three arms must have roads, and
  every straight-through path between connected arms must be forced to bend around the island. A T-junction cut from a
  4-arm roundabout prefab therefore counts as a plain intersection. Rings assembled from many small road pieces (some
  ProMods roundabouts) are not detected and get ordinary turn prompts.
* Heading is derived from movement while driving, so it does not depend on the plugin's angle convention.
* Sector files older than format 884 are skipped (the companion reports how many).
* `android/app/src/test` has a JVM test that loads a real `etsmap.bin` (set env `ETS2_MAP`), routes Paris to Berlin and
  prints maneuvers/prompts: `gradle -p android testDebugUnitTest`.
* `companion/tools/verify.py <map> <city> <city> out.png` routes between two cities and renders a PNG;
  `tools/guidance_ref.py` prints the generated maneuvers - handy for checking a map build.

Map parsing is a Python port of the file-format knowledge in the MIT-licensed
[ts-map](https://github.com/dariowouters/ts-map) project.


## License

MIT - see [LICENSE](LICENSE). ETS2 / Euro Truck Simulator 2 is a trademark of SCS Software; this project is not affiliated with or endorsed by SCS Software. No game data is distributed: the map is built locally from your own installation.

