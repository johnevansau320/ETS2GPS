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

- **Saved trips**: after planning a route (and any waypoints) tap **Trips > Save current trip** and give it a name. Saved trips keep the destination, waypoints and route type; tap one later to start it from wherever you are, or delete it.
- **Waypoints**: once a route exists, search a city and choose *Add as waypoint*, long-press the map and choose *Add as waypoint*, or open **Stops** (Search > Stops) to add, remove, reorder or clear them. Waypoints show as numbered blue markers; the app guides you to each in turn ("Waypoint reached") and the distance/ETA cover the whole trip.
* **POIs** (from the game's own prefab spawn points and map overlays): fuel stations, service centres, rest areas /
  parking, weigh stations, garages, truck dealers. Shown as F / P / S / W badges on the map; the **POI** button lists the
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
* **Route types** (gear -> Route type, or the quick button in the bottom bar that cycles Heavy / Fast / Tour):
  **Heavy transport** (default) strongly prefers motorways and major roads and avoids small roads and tight town
  junctions; **Fastest** is pure travel time; **Tourist** prefers smaller, scenic roads and avoids motorways. Road grades
  (motorway / expressway / wide local / minor) come from each road's lane types in the game data. Changing the mode
  re-plans the active route immediately.
* **Trip / job screen**: the blue **Trip** button (top of the left column) swaps the map for a dashboard modelled on the
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

