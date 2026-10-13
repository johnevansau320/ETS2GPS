package com.ets2gps

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ListView
import android.widget.TextView
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    companion object {
        /** ETS2 runs its clock 19x faster than real time. */
        const val TIME_SCALE = 19f
        const val LANE_SHOW_M = 50_000f // lane panel appears this far (in game metres) before the junction
    }

    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var mapView: MapView
    private lateinit var banner: TurnBanner
    private lateinit var compass: CompassView
    private lateinit var laneView: LaneView
    private lateinit var recentreBtn: Button
    private lateinit var tripScreen: TripScreen
    private lateinit var tripBtn: Button
    private lateinit var zoomBox: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private var routeRemainingM = -1f
    private var lastNextText = ""

    // trip statistics (reset with the button on the trip screen and when a new job starts)
    private var tripBase = 0f
    private var tripDriveMs = 0L
    private var tripMaxKmh = 0f
    private var tripFuelUsed = 0f
    private var tripLastFuel = -1f
    private var lastTripMs = 0L
    private lateinit var speedText: TextView
    private lateinit var limitText: TextView
    private lateinit var altText: TextView
    private val elev = ElevationLog()
    private var descStart = -1f
    private var climbStart = -1f
    private var lastDescWarn = 0L
    private var lastClimbWarn = 0L
    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var voice: Voice
    private lateinit var voiceBtn: Button
    private lateinit var modeBtn: Button
    private var routeMode: RouteMode
        get() = RouteMode.values().getOrElse(prefs.getInt("route_mode", 0)) { RouteMode.HEAVY }
        set(v) {
            prefs.edit().putInt("route_mode", v.ordinal).apply()
            router?.mode = v
            if (::modeBtn.isInitialized) updateModeButton()
        }
    private var voiceProblemShown = false
    private lateinit var telemetry: TelemetryClient

    private var map: MapData? = null
    private var router: Router? = null
    private var nav: Navigator? = null
    private val motion = MotionFilter()
    private var truck: TruckState? = null
    private var destX = 0f
    private var destZ = 0f
    private var destName = ""
    private var hasDest = false
    private val routing = AtomicBoolean(false)
    private var lastReroute = 0L
    private var downloading = false
    private var destIsPoi = false
    private var resumeSet = false
    private var resumeX = 0f
    private var resumeZ = 0f
    private var resumeName = ""
    private var fuelAlerted = false
    private var rest30 = false
    private var rest10 = false
    private var lastPlaceCheck = 0L
    private var place = ""
    private var routeEtaGameMin = -1f
    private val avoidPoints = ArrayList<Pair<Float, Float>>()

    /** An intermediate stop on the way to the final destination (destX/destZ). */
    private class Stop(val x: Float, val z: Float, val name: String)
    private val waypoints = ArrayList<Stop>()
    private val resumeStops = ArrayList<Stop>() // waypoints set aside while a POI detour is active
    private var startLabel = "Start" // where the current plan began (for naming saved trips)
    private var laneHidden = false // the Trip screen is open
    private var afterM = 0f // distance / time of the legs after the one being driven
    private var afterS = 0f
    private var paceRatio = 1f // actual speed / speed the router assumed, smoothed (~90 s)
    private var lastPaceMs = 0L

    private val prefs by lazy { getSharedPreferences("ets2gps", Context.MODE_PRIVATE) }
    private var host: String
        get() = cleanHost(prefs.getString("host", "") ?: "")
        set(v) { prefs.edit().putString("host", cleanHost(v)).apply() }
    private var distScale: Float
        get() = prefs.getFloat("scale", 19f)
        set(v) { prefs.edit().putFloat("scale", v).apply() }

    /** Accepts "192.168.1.5", "http://192.168.1.5:31377/", etc. and returns just the host name / IP. */
    private fun cleanHost(v: String): String =
        v.trim().substringAfter("://").substringBefore("/").substringBefore(":").trim()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------------ lifecycle --

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        voice = Voice(this)
        voice.enabled = prefs.getBoolean("voice", true)
        voice.onProblem = { msg -> ui.post { showVoiceProblem(msg) } }
        voice.onNotice = { msg -> ui.post { toast(msg) } }
        voice.pcHost = { host }
        voice.output = prefs.getInt("voice_out", 0)
        updateVoiceButton()
        telemetry = TelemetryClient({ host }, ::onTruck, ::onStatus)
        loadMapAsync()
        if (host.isEmpty()) {
            banner.setMessage("Tap the gear to set your PC address")
        }
    }

    override fun onStart() {
        super.onStart()
        telemetry.start()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onPause()
    }

    override fun onStop() {
        telemetry.stop()
        super.onStop()
    }

    override fun onDestroy() {
        voice.shutdown()
        bg.shutdownNow()
        super.onDestroy()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val p = motion.predicted(SystemClock.elapsedRealtime())
            mapView.truckX = p.first
            mapView.truckZ = p.second
            mapView.bearing = motion.bearing
            mapView.speedMps = motion.speedMps
            compass.setHeading(mapView.viewBearing, mapView.hasFix)
            mapView.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // --------------------------------------------------------------------------------- UI --

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(200, 40, 52, 70))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        mapView = MapView(this)
        mapView.onLongPress = { x, z -> confirmDestination(x, z) }
        root.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        tripScreen = TripScreen(this)
        tripScreen.visibility = View.GONE
        tripScreen.onReset = { resetTrip(); updateTrip() }
        root.addView(tripScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(108); topMargin = dp(6); bottomMargin = dp(6)
        })

        banner = TurnBanner(this)
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = dp(14) })

        statusText = TextView(this).apply {
            setTextColor(Color.argb(220, 255, 200, 120)); textSize = 12f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(statusText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(118); rightMargin = dp(12)
        })

        // zoom buttons
        val zoom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoomBox = zoom
        zoom.addView(button("+") { mapView.zoomBy(1.3f) }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        zoom.addView(button("−") { mapView.zoomBy(1f / 1.3f) }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL }.apply { topMargin = dp(8) })
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(24) })

        // recentre: appears only while the map is detached from the truck (after dragging it)
        recentreBtn = button("\u25CE  Recentre") { mapView.recentre() }
        recentreBtn.textSize = 16f
        recentreBtn.setBackgroundColor(Color.argb(235, 30, 120, 70))
        recentreBtn.visibility = View.GONE
        root.addView(recentreBtn, FrameLayout.LayoutParams(dp(150), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(24); bottomMargin = dp(96)
        })
        mapView.onFreeChanged = { free -> recentreBtn.visibility = if (free) View.VISIBLE else View.GONE }

        // lane guidance: centred under the green bar, shown when the next junction has lane data
        laneView = LaneView(this)
        laneView.visibility = View.GONE
        root.addView(laneView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(106)
        })

        // compass: right edge, halfway between the bottom of the green bar and the top of the zoom buttons
        compass = CompassView(this)
        val compassLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        root.addView(compass, compassLp)

        // altitude + incline: left of the compass, twice the old text size (tap for the elevation chart)
        altText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; text = "-- m"
            setBackgroundColor(Color.argb(190, 14, 18, 24))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setOnClickListener { showElevation() }
        }
        val altLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(100), Gravity.TOP or Gravity.END).apply { rightMargin = dp(108) }
        root.addView(altText, altLp)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val barBottom = dp(14 + 96 - 6)
            val zoomTop = h / 2 - dp(56)
            val wanted = ((barBottom + zoomTop) / 2 - dp(50)).coerceAtLeast(barBottom)
            if (altLp.topMargin != wanted) {
                altLp.topMargin = wanted
                altText.post { altText.requestLayout() }
            }
        }
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val barBottom = dp(14 + 96 - 6) // banner top margin + height - inner inset
            val zoomTop = h / 2 - dp(56) // zoom column is 52 + 8 + 52 dp tall, centred vertically
            val size = dp(100)
            val centre = (barBottom + zoomTop) / 2
            val wanted = (centre - size / 2).coerceAtLeast(barBottom)
            if (compassLp.topMargin != wanted) {
                compassLp.topMargin = wanted
                compass.post { compass.requestLayout() }
            }
        }

        // bottom bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(225, 14, 18, 24))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        speedText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 34f; typeface = Typeface.DEFAULT_BOLD; text = "0"
        }
        val speedUnit = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 11f; text = "km/h" }
        val speedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        speedBox.addView(speedText); speedBox.addView(speedUnit)
        bar.addView(speedBox, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))

        limitText = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE); visibility = View.INVISIBLE; text = "--"
        }
        bar.addView(limitText, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })


        infoText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; text = "No route" }
        bar.addView(infoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bottomBar = bar
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // action buttons: vertical column on the left edge, between the green bar and the bottom bar
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        fun menuButton(b: Button) {
            b.textSize = 14f
            b.setPadding(dp(4), 0, dp(4), 0)
            menu.addView(b, LinearLayout.LayoutParams(dp(88), dp(46)).apply { topMargin = dp(3); bottomMargin = dp(3) })
        }
        tripBtn = button("Trip") { toggleTrip() }
        tripBtn.setBackgroundColor(Color.rgb(25, 118, 210))
        menuButton(tripBtn)
        menu.addView(View(this), LinearLayout.LayoutParams(dp(88), dp(10)))
        menuButton(button("Search") { showSearch() })
        menuButton(button("Stops") { showStops() })
        menuButton(button("Trips") { showTrips() })
        modeBtn = button("Heavy") { cycleRouteMode() }
        menuButton(modeBtn)
        updateModeButton()
        voiceBtn = button("Voice on") { toggleVoice() }
        menuButton(voiceBtn)
        menuButton(button("POI") { showPoiPicker() })
        menuButton(button("Job") { routeToJob() })
        menuButton(button("Stop") { cancelRoute() })
        menuButton(button("\u2699") { showSettings() })
        val menuScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(menu)
        }
        root.addView(menuScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(112); bottomMargin = dp(84)
        })

        setContentView(root)
    }

    private fun updateModeButton() {
        modeBtn.text = routeMode.short
    }

    /** Heavy -> Fast -> Tour -> Heavy ..., re-planning the current route straight away. */
    private fun cycleRouteMode() {
        val all = RouteMode.values()
        routeMode = all[(routeMode.ordinal + 1) % all.size]
        toast("Route mode: ${routeMode.label}")
        voice.say("${routeMode.label} routing")
        rerouteForModeChange()
    }

    private fun rerouteForModeChange() {
        if (hasDest && nav?.active == true && !routing.get()) {
            computeRoute(destX, destZ, destName, announce = false, reroute = true)
        }
    }

    private fun updateVoiceButton() {
        voiceBtn.text = if (voice.enabled) "Voice on" else "Voice off"
    }

    private fun toggleVoice() {
        voice.enabled = !voice.enabled
        prefs.edit().putBoolean("voice", voice.enabled).apply()
        updateVoiceButton()
        if (voice.enabled) voice.test()
    }

    private fun showVoiceProblem(msg: String) {
        if (voiceProblemShown) return
        voiceProblemShown = true
        AlertDialog.Builder(this)
            .setTitle("Voice guidance unavailable")
            .setMessage(msg)
            .setPositiveButton("Voice settings") { _, _ ->
                try {
                    startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS"))
                } catch (e: Exception) {
                    try {
                        startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    } catch (e2: Exception) {
                        toast("Open Android Settings > System > Languages > Text-to-speech")
                    }
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------------------- map file --

    private fun mapFile() = File(filesDir, "etsmap.bin")

    private fun loadMapAsync() {
        val f = mapFile()
        if (!f.exists()) {
            if (host.isNotEmpty()) banner.setMessage("No map yet - open settings and tap Download map")
            return
        }
        banner.setMessage("Loading map...")
        bg.execute {
            try {
                val m = MapData.load(f)
                val r = Router(m)
                r.mode = routeMode
                loadAvoid()
                r.setAvoid(avoidPoints)
                val n = Navigator(m, r)
                ui.post {
                    map = m; router = r; nav = n
                    n.distScale = distScale
                    mapView.map = m
                    banner.setMessage(null)
                    toast("Map loaded: ${m.cities.size} cities, ${m.edgeCount} road segments" + (if (m.laneN.isEmpty()) " - no lane data: update the companion and download the map again" else ", with lane data"))
                }
            } catch (t: Throwable) {
                ui.post { banner.setMessage("Could not load map: ${t.message}") }
            }
        }
    }

    private fun downloadMap() {
        if (downloading) return
        val h = host.substringBefore(":")
        if (h.isEmpty()) { toast("Set the PC address first"); return }
        downloading = true
        bg.execute {
            val tmp = File(filesDir, "etsmap.bin.tmp")
            try {
                val c = URL("http://$h:31378/map.bin").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 15000
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    ui.post { banner.setMessage("Downloading map... $pct%") }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!tmp.renameTo(mapFile())) {
                    mapFile().delete()
                    tmp.renameTo(mapFile())
                }
                ui.post { downloading = false; loadMapAsync() }
            } catch (t: Throwable) {
                tmp.delete()
                ui.post {
                    downloading = false
                    banner.setMessage("Download failed: ${t.javaClass.simpleName} ${t.message ?: ""} - is the companion running?")
                }
            }
        }
    }

    // --------------------------------------------------------------------------- telemetry --

    private fun onStatus(text: String) {
        statusText.text = text
        mapView.hasFix = false
    }

    private fun onTruck(s: TruckState) {
        truck = s
        banner.setJob(s.job, if (nav?.active == true && routeEtaGameMin >= 0) routeEtaGameMin else s.navEtaMin)
        val now = SystemClock.elapsedRealtime()
        motion.update(s, now)
        mapView.hasFix = s.connected && (s.x != 0f || s.z != 0f)
        statusText.text = if (!s.connected) "Telemetry connected - waiting for the game" else if (s.paused) "Game paused" else place
        speedText.text = Math.round(s.speedKmh.coerceAtLeast(0f)).toString()
        if (s.speedLimitKmh > 0) {
            limitText.text = s.speedLimitKmh.toString(); limitText.visibility = View.VISIBLE
        } else limitText.visibility = View.INVISIBLE
        trackTrip(s, now)
        if (tripScreen.visibility == View.VISIBLE) updateTrip()
        if (s.connected) { updateAltitude(s); checkHill(s); checkNewJob(s); checkAlerts(s); updatePlace(now); navTick() }
    }

    // ------------------------------------------------------------------------- navigation --

    private fun navTick() {
        val n = nav ?: return
        if (!n.active) return
        val st = n.update(motion.x, motion.z, motion.speedMps)
        mapView.routeIdx = n.currentIndex
        mapView.nextManeuver = st.next
        if (st.speech != null) voice.say(st.speech)
        if (st.arrived && waypoints.isNotEmpty()) {
            val wp = waypoints.removeAt(0)
            n.clear()
            syncStops()
            val nextName = waypoints.firstOrNull()?.name ?: destName
            voice.say("Waypoint reached. Continuing to $nextName")
            toast("Waypoint reached: ${wp.name}")
            computeRoute(destX, destZ, destName, announce = false, reroute = true)
            return
        }
        if (st.arrived && resumeSet) {
            resumeSet = false
            n.clear()
            voice.say("Back on route to $resumeName")
            waypoints.clear(); waypoints.addAll(resumeStops); resumeStops.clear()
            syncStops()
            computeRoute(resumeX, resumeZ, resumeName, announce = false, keepStops = true)
            return
        }
        if (st.arrived) {
            infoText.text = "Arrived at $destName"
            banner.setMessage("You have arrived")
            n.clear()
            mapView.routePts = FloatArray(0); mapView.routeEdgePt = IntArray(0); mapView.nextManeuver = null; setLaneUi(null)
            hasDest = false
            return
        }
        val next = st.next
        lastNextText = if (next != null) (n.instruction(next) + " in " + n.shortDistance(st.distToNextM) + (next.towards?.let { " towards $it" } ?: "")) else ""
        val totM = st.remainingM + afterM
        val totS = st.remainingS + afterS
        routeRemainingM = totM
        if (!laneHidden && st.lanes != null && st.lanesDistM * distScale < LANE_SHOW_M) setLaneUi(st.lanes, n.shortDistance(st.lanesDistM)) else setLaneUi(null)
        if (next != null) banner.setManeuver(next, n.shortDistance(st.distToNextM), n.instruction(next).replaceFirstChar { it.uppercase() } + (next.towards?.let { " towards $it" } ?: ""), st.following)
        updatePace(n)
        val realMin = totS / paceRatio / 60f // real-world minutes at your actual pace
        val gameMin = realMin * TIME_SCALE // the same trip on the in-game clock
        routeEtaGameMin = gameMin
        val clock = etaClock(gameMin)
        val label = if (waypoints.isEmpty()) destName else "$destName (via ${waypoints.size} stop${if (waypoints.size > 1) "s" else ""}, next: ${waypoints[0].name})"
        infoText.text = "$label\n${n.shortDistance(totM)} · Real ${formatDuration(realMin)} · Sim ${formatDuration(gameMin)}" +
            (if (clock.isNotEmpty()) " · Arrive $clock" else "")
        if (st.offRoute) rerouteIfNeeded()
    }

    /** Learns how fast the truck really travels compared with the router's speeds, so the ETA follows reality. */
    private fun updatePace(n: Navigator) {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastPaceMs) / 1000f
        lastPaceMs = now
        val model = n.modelSpeedMps
        val actual = motion.speedMps
        if (dt <= 0f || dt > 5f || model < 5f || actual < 3f) return // stopped, in a junction or a gap in updates
        val ratio = (actual / model).coerceIn(0.35f, 1.3f)
        val a = 1f - Math.exp((-dt / 90f).toDouble()).toFloat()
        paceRatio = (paceRatio + (ratio - paceRatio) * a).coerceIn(0.4f, 1.2f)
    }

    /** Arrival time on the game clock, e.g. "Thu 14:35" (day 1 of the game calendar is a Monday). */
    private fun etaClock(gameMin: Float): String {
        val t = truck ?: return ""
        if (t.gameTimeMin < 0f) return ""
        val abs = t.gameTimeMin + gameMin
        val day = (abs / 1440f).toInt()
        val inDay = abs - day * 1440f
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val today = (t.gameTimeMin / 1440f).toInt()
        val extra = if (day > today + 6) " (+${day - today}d)" else ""
        return "${names[day % 7]} ${"%02d".format((inDay / 60f).toInt())}:${"%02d".format((inDay % 60f).toInt())}$extra"
    }

    /** Days / hours / minutes, e.g. "1d 2h 05m", "2h 05m", "38m". */
    private fun formatDuration(min: Float): String {
        val m = Math.round(min)
        return when {
            m >= 1440 -> "${m / 1440}d ${(m % 1440) / 60}h ${"%02d".format(m % 60)}m"
            m >= 60 -> "${m / 60}h ${"%02d".format(m % 60)}m"
            else -> "${m}m"
        }
    }

    private fun rerouteIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (!hasDest || routing.get() || now - lastReroute < 4000) return
        lastReroute = now
        voice.say("Recalculating")
        computeRoute(destX, destZ, destName, announce = false, reroute = true)
    }

    private fun cancelRoute() {
        resumeSet = false; destIsPoi = false
        waypoints.clear(); resumeStops.clear(); afterM = 0f; afterS = 0f; syncStops()
        nav?.clear()
        routeEtaGameMin = -1f
        routeRemainingM = -1f
        lastNextText = ""
        paceRatio = 1f
        hasDest = false
        mapView.routePts = FloatArray(0); mapView.routeEdgePt = IntArray(0); mapView.nextManeuver = null; setLaneUi(null)
        banner.setMessage(null)
        infoText.text = "No route"
    }

    private fun computeRoute(x: Float, z: Float, name: String, announce: Boolean, isPoi: Boolean = false, reroute: Boolean = false, keepStops: Boolean = false) {
        val r = router; val n = nav; val m = map
        if (r == null || n == null || m == null) { toast("Map not loaded yet"); return }
        if (truck?.connected != true) { toast("Start the game first - no truck position yet"); return }
        if (!routing.compareAndSet(false, true)) return
        if (!reroute) {
            if (isPoi) {
                if (hasDest && !destIsPoi) {
                    resumeSet = true; resumeX = destX; resumeZ = destZ; resumeName = destName
                    resumeStops.clear(); resumeStops.addAll(waypoints)
                }
                waypoints.clear()
            } else {
                resumeSet = false
                if (!keepStops) waypoints.clear()
            }
            destIsPoi = isPoi
        }
        destX = x; destZ = z; destName = name; hasDest = true
        if (!reroute && !isPoi && !keepStops) startLabel = place.removePrefix("Near ").ifEmpty { "Start" }
        syncStops()
        // the leg being driven ends at the next waypoint, or at the destination when none are left
        val leg = waypoints.firstOrNull()
        val tx = leg?.x ?: x; val tz = leg?.z ?: z; val tname = leg?.name ?: name
        val chain = waypoints.drop(1).map { Pair(it.x, it.z) } + Pair(x, z) // the legs after this one
        val legIsStop = leg != null
        val sx = motion.x; val sz = motion.z; val brg = motion.bearing
        if (announce) banner.setMessage("Calculating route to $tname...")
        bg.execute {
            try {
                val snap = r.snap(sx, sz, brg, 60f) ?: r.snap(sx, sz, null, 200f)
                if (snap == null) {
                    ui.post { banner.setMessage("You are not on a known road") }
                    return@execute
                }
                val node = r.nearestMainNode(tx, tz)
                if (node < 0) { ui.post { banner.setMessage("Destination is not reachable by road") }; return@execute }
                val remaining = (m.eLen[snap.de shr 1] - snap.along).coerceAtLeast(1f)
                val res = r.route(snap.de, remaining, node)
                if (res == null) { ui.post { banner.setMessage("No route found") }; return@execute }
                // length / time of the remaining legs (waypoint to waypoint, then to the destination)
                var am = 0f; var asec = 0f; var prev = node
                if (legIsStop) for ((cx, cz) in chain) {
                    val nn = r.nearestMainNode(cx, cz)
                    if (nn < 0 || nn == prev || r.outCount(prev) == 0) continue
                    val ide = r.outAt(prev, 0)
                    val rr = r.route(ide, m.eLen[ide shr 1], nn)
                    if (rr != null) { am += rr.lengthM; asec += rr.timeS }
                    prev = nn
                }
                // flatten the route geometry for drawing
                var total = 0
                for (de in res.edges) total += m.deCount(de)
                val pts = FloatArray(total * 2)
                val starts = IntArray(res.edges.size)
                var p = 0
                for ((i, de) in res.edges.withIndex()) {
                    starts[i] = p
                    for (k in 0 until m.deCount(de)) {
                        pts[p * 2] = m.deX(de, k); pts[p * 2 + 1] = m.deZ(de, k); p++
                    }
                }
                ui.post {
                    n.distScale = distScale
                    n.arriveWord = if (legIsStop) "your waypoint" else "your destination"
                    afterM = am; afterS = asec
                    n.setRoute(res, remaining)
                    mapView.routePts = pts; mapView.routeEdgePt = starts; mapView.routeIdx = 0
                    banner.setMessage(null)
                    if (announce) {
                        voice.say(if (legIsStop) "Route to $tname, the first of ${waypoints.size + 1} stops. ${n.spokenDistance(res.lengthM)}. ${routeMode.label} routing." else "Route to $name. ${n.spokenDistance(res.lengthM)}. ${routeMode.label} routing.")
                    }
                    navTick()
                }
            } catch (t: Throwable) {
                ui.post { banner.setMessage("Routing failed: ${t.message}") }
            } finally {
                routing.set(false)
            }
        }
    }

    private fun routeToJob() {
        val m = map
        val t = truck
        if (m == null) { toast("Map not loaded yet"); return }
        if (t == null || !t.connected) { toast("Start the game first - no telemetry yet"); return }
        if (t.destCityId.isEmpty() && t.destCity.isEmpty()) { toast("The game reports no active job"); return }
        navigateToJob(t, m)
    }

    /** Routes to the delivery company if it is on the map, otherwise to the destination city centre. */
    private fun navigateToJob(t: TruckState, m: MapData) {
        val co = m.findCompany(t.destCityId, t.destCompanyId)
        if (co != null) {
            val label = if (t.destCompany.isNotEmpty()) "${t.destCompany}, ${t.destCity}" else t.destCity
            computeRoute(co.x, co.z, label, announce = true)
            return
        }
        val c = m.findCity(t.destCityId)
            ?: m.cities.firstOrNull { it.key == City.normalize(t.destCity) }
        if (c == null) {
            toast("Job destination '${t.destCity}' (id '${t.destCityId}') is not on the loaded map")
            return
        }
        computeRoute(c.x, c.z, c.name, announce = true)
    }

    private var lastJobKey = ""
    private var jobDialog: AlertDialog? = null

    /** Offers to navigate when the game reports a new job. */
    private fun checkNewJob(t: TruckState) {
        val m = map ?: return
        val key = if (t.destCityId.isEmpty()) "" else "${t.destCityId}/${t.destCompanyId}"
        if (key == lastJobKey) return
        lastJobKey = key
        if (key.isNotEmpty()) resetTrip()
        if (key.isEmpty() || jobDialog?.isShowing == true) return
        val name = if (t.destCompany.isNotEmpty()) "${t.destCompany}, ${t.destCity}" else t.destCity
        jobDialog = AlertDialog.Builder(this)
            .setTitle("New job")
            .setMessage("Navigate to $name?")
            .setPositiveButton("Navigate") { _, _ -> navigateToJob(t, m) }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ------------------------------------------------------------- points of interest --

    private class PoiHit(val poi: Poi, val dist: Float, val ahead: Float, val right: Float)

    private fun nearestPois(type: Int, limit: Int): List<PoiHit> {
        val m = map ?: return emptyList()
        val x = motion.x; val z = motion.z; val b = motion.bearing
        val fx = sin(b); val fz = -cos(b); val rx = cos(b); val rz = sin(b)
        val hits = ArrayList<PoiHit>()
        for (p in m.pois) {
            if (p.type != type) continue
            val dx = p.x - x; val dz = p.z - z
            if (dx > 15000f || dx < -15000f || dz > 15000f || dz < -15000f) continue
            val d = hypot(dx, dz)
            hits.add(PoiHit(p, d, (dx * fx + dz * fz) / max(d, 1f), (dx * rx + dz * rz) / max(d, 1f)))
        }
        // things behind you count double: you cannot turn around on a motorway
        return hits.sortedBy { if (it.ahead < -0.3f) it.dist * 2f else it.dist }.take(limit)
    }

    private fun gameDist(worldM: Float): String {
        val g = worldM * distScale
        return if (g < 1000f) "${max(10, (g / 10f).roundToInt() * 10)} m" else String.format(java.util.Locale.US, "%.1f km", g / 1000f)
    }

    private fun direction(h: PoiHit): String = when {
        h.ahead > 0.5f -> "ahead"
        h.ahead < -0.5f -> "behind"
        h.right > 0f -> "to the right"
        else -> "to the left"
    }

    private fun showPoiPicker() {
        if (map == null) { toast("Map not loaded yet"); return }
        val types = intArrayOf(Poi.FUEL, Poi.PARKING, Poi.SERVICE, Poi.WEIGH, Poi.GARAGE, Poi.DEALER)
        val names = types.map { Poi.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Find nearby")
            .setItems(names) { _, i -> showPoiList(types[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPoiList(type: Int) {
        if (truck?.connected != true) { toast("Start the game first - no truck position yet"); return }
        val hits = nearestPois(type, 15)
        if (hits.isEmpty()) { toast("No ${Poi.label(type).lowercase()} within range"); return }
        val labels = hits.map { "${Poi.label(type)}  –  ${gameDist(it.dist)} ${direction(it)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(Poi.label(type) + "s nearby")
            .setItems(labels) { _, i ->
                val h = hits[i]
                computeRoute(h.poi.x, h.poi.z, Poi.label(type), announce = true, isPoi = true)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------- trip / job screen --

    private fun toggleTrip() {
        val show = tripScreen.visibility != View.VISIBLE
        tripScreen.visibility = if (show) View.VISIBLE else View.GONE
        zoomBox.visibility = if (show) View.GONE else View.VISIBLE
        compass.visibility = if (show) View.GONE else View.VISIBLE
        laneHidden = show
        if (show) setLaneUi(null)
        altText.visibility = if (show) View.GONE else View.VISIBLE
        recentreBtn.visibility = if (show || !mapView.free) View.GONE else View.VISIBLE
        tripBtn.text = if (show) "Map" else "Trip"
        banner.visibility = if (show) View.GONE else View.VISIBLE // the dashboard uses the whole screen
        bottomBar.visibility = if (show) View.GONE else View.VISIBLE
        statusText.visibility = if (show) View.GONE else View.VISIBLE
        if (show) updateTrip()
    }

    private fun resetTrip() {
        tripBase = motion.travelled
        tripDriveMs = 0L
        tripMaxKmh = 0f
        tripFuelUsed = 0f
        tripLastFuel = -1f
        elev.clear()
    }

    private fun trackTrip(s: TruckState, now: Long) {
        val dt = (now - lastTripMs).coerceIn(0L, 2000L)
        lastTripMs = now
        if (!s.connected) return
        if (s.speedKmh > 3f) tripDriveMs += dt
        tripMaxKmh = max(tripMaxKmh, s.speedKmh)
        val f = s.detail?.fuelL ?: -1f
        if (f > 0f) {
            if (tripLastFuel > 0f && f < tripLastFuel) tripFuelUsed += tripLastFuel - f // refuelling does not count
            tripLastFuel = f
        }
    }

    private fun clockText(absMin: Float): String {
        if (absMin < 0f) return "-"
        val day = (absMin / 1440f).toInt()
        val inDay = absMin - day * 1440f
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return "${names[day % 7]} ${"%02d".format((inDay / 60f).toInt())}:${"%02d".format((inDay % 60f).toInt())}"
    }

    private fun pct(v: Float) = String.format(java.util.Locale.US, "%.0f%%", v)

    private fun updateTrip() {
        val s = truck
        val cards = ArrayList<TripCard>()
        if (s == null || !s.connected) {
            cards.add(TripCard("WAITING", listOf(TripRow("Game", "not connected"))))
            tripScreen.setCards(cards, null)
            return
        }
        val j = s.job
        val dt = s.detail
        val n = nav
        val white = Color.WHITE
        val amber = Color.rgb(255, 190, 90)
        val red = Color.rgb(255, 120, 110)
        val routing = n?.active == true && routeRemainingM >= 0f
        val remGameKm = if (routing) routeRemainingM * distScale / 1000f else (dt?.navDistanceKm ?: 0f)
        val realMin = if (routing && routeEtaGameMin >= 0f) routeEtaGameMin / TIME_SCALE else -1f
        val simMin = if (routing) routeEtaGameMin else (if (s.navEtaMin >= 0f) s.navEtaMin else -1f)

        // JOB
        if (j != null) {
            val rows = ArrayList<TripRow>()
            rows.add(TripRow("From", listOf(j.srcCompany, j.srcCity).filter { it.isNotEmpty() }.joinToString(", ")))
            rows.add(TripRow("To", listOf(j.destCompany, j.destCity).filter { it.isNotEmpty() }.joinToString(", ")))
            rows.add(TripRow("Cargo", j.cargo + (if (j.massKg > 0f) String.format(java.util.Locale.US, "  %.1f t", j.massKg / 1000f) else "")))
            if (j.income > 0) rows.add(TripRow("Income", "\u20ac" + String.format(java.util.Locale.US, "%,d", j.income)))
            if (j.dueMin >= 0f) {
                val late = simMin >= 0f && simMin > j.dueMin
                rows.add(TripRow("Deadline in", formatDuration(j.dueMin) + " (sim)", if (late) red else white))
                if (simMin >= 0f) {
                    val spare = j.dueMin - simMin
                    rows.add(TripRow(if (late) "Running late by" else "Time to spare", formatDuration(Math.abs(spare)) + " (sim)", if (late) red else Color.rgb(120, 220, 150)))
                }
            }
            if (j.plannedKm > 0) rows.add(TripRow("Planned distance", "${j.plannedKm} km"))
            var bar: Float? = null
            var note = ""
            if (j.plannedKm > 0 && remGameKm > 0f) {
                bar = (1f - remGameKm / j.plannedKm).coerceIn(0f, 1f)
                note = String.format(java.util.Locale.US, "%.0f%% of the job driven", bar * 100f)
            }
            cards.add(TripCard("JOB", rows, bar, Color.rgb(90, 190, 255), note))
        } else {
            cards.add(TripCard("JOB", listOf(TripRow("Status", "no active job"))))
        }

        // ROUTE
        val rr = ArrayList<TripRow>()
        rr.add(TripRow("Destination", if (hasDest) destName else "none"))
        if (routing && n != null) {
            rr.add(TripRow("Remaining", n.shortDistance(routeRemainingM)))
            rr.add(TripRow("Real time left", formatDuration(realMin)))
            rr.add(TripRow("Sim time left", formatDuration(simMin)))
            rr.add(TripRow("Arrive (sim clock)", clockText(s.gameTimeMin + simMin)))
            if (lastNextText.isNotEmpty()) rr.add(TripRow("Next", lastNextText.replaceFirstChar { it.uppercase() }))
        } else {
            rr.add(TripRow("Game route left", String.format(java.util.Locale.US, "%.0f km", remGameKm)))
            if (simMin >= 0f) rr.add(TripRow("Game ETA (sim)", formatDuration(simMin)))
        }
        rr.add(TripRow("Route type", routeMode.label))
        cards.add(TripCard("ROUTE", rr))

        // TRUCK
        if (dt != null) {
            val fr = if (dt.fuelCapL > 0f) dt.fuelL / dt.fuelCapL else 0f
            val tr = ArrayList<TripRow>()
            tr.add(TripRow("Truck", dt.truckName.ifEmpty { "-" }))
            tr.add(TripRow("Fuel", String.format(java.util.Locale.US, "%.0f / %.0f L  (%.0f%%)", dt.fuelL, dt.fuelCapL, fr * 100f), if (fr < 0.12f) red else if (fr < 0.25f) amber else white))
            tr.add(TripRow("Range", String.format(java.util.Locale.US, "%.0f km", dt.rangeKm)))
            tr.add(TripRow("Consumption", String.format(java.util.Locale.US, "%.1f L/100 km", dt.consumptionLPerKm * 100f)))
            tr.add(TripRow("Odometer", String.format(java.util.Locale.US, "%,.0f km", dt.odometerKm)))
            tr.add(TripRow("Speed", "${Math.round(s.speedKmh.coerceAtLeast(0f))} km/h" + (if (s.speedLimitKmh > 0) "  (limit ${s.speedLimitKmh})" else "")))
            cards.add(TripCard("TRUCK", tr, fr, if (fr < 0.12f) red else if (fr < 0.25f) amber else Color.rgb(110, 200, 130), "fuel tank"))

            // TRAILER / DAMAGE
            if (dt.trailerAttached) {
                val rows = listOf(
                    TripRow("Trailer", dt.trailerName.ifEmpty { "-" }),
                    TripRow("Plate", dt.trailerPlate.ifEmpty { "-" }),
                    TripRow("Chassis damage", pct(dt.trailerChassis), TripScreen.pctColor(dt.trailerChassis)),
                    TripRow("Wheels damage", pct(dt.trailerWheels), TripScreen.pctColor(dt.trailerWheels)),
                    TripRow("Body damage", pct(dt.trailerBody), TripScreen.pctColor(dt.trailerBody)),
                    TripRow("Cargo damage", pct(dt.cargoDamage), TripScreen.pctColor(dt.cargoDamage)),
                )
                cards.add(TripCard("TRAILER & CARGO", rows))
            } else {
                cards.add(TripCard("TRAILER & CARGO", listOf(TripRow("Trailer", "none attached"))))
            }
        }

        // THIS TRIP
        val drivenGameKm = max(0f, motion.travelled - tripBase) * distScale / 1000f
        val driveHours = tripDriveMs / 3_600_000f
        val avg = if (driveHours > 0.001f) (motion.travelled - tripBase) / (tripDriveMs / 1000f) * 3.6f else 0f
        val sr = ArrayList<TripRow>()
        sr.add(TripRow("Distance driven", String.format(java.util.Locale.US, "%.0f km", drivenGameKm)))
        sr.add(TripRow("Driving time (real)", formatDuration(tripDriveMs / 60000f)))
        sr.add(TripRow("Driving time (sim)", formatDuration(tripDriveMs / 60000f * TIME_SCALE)))
        sr.add(TripRow("Average speed", String.format(java.util.Locale.US, "%.0f km/h", avg)))
        sr.add(TripRow("Top speed", String.format(java.util.Locale.US, "%.0f km/h", tripMaxKmh)))
        sr.add(TripRow("Climbed / descended", String.format(java.util.Locale.US, "%.0f / %.0f m", elev.ascent(), elev.descent())))
        sr.add(TripRow("Fuel used", String.format(java.util.Locale.US, "%.0f L", tripFuelUsed)))
        if (drivenGameKm > 5f && tripFuelUsed > 0f) {
            sr.add(TripRow("Trip consumption", String.format(java.util.Locale.US, "%.1f L/100 km", tripFuelUsed / drivenGameKm * 100f)))
        }
        cards.add(TripCard("THIS TRIP", sr))

        // TIME & REST
        val tr2 = ArrayList<TripRow>()
        tr2.add(TripRow("Sim clock", clockText(s.gameTimeMin)))
        val cal = java.util.Calendar.getInstance()
        tr2.add(TripRow("Real time", String.format(java.util.Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))))
        if (s.nextRestMin >= 0f) {
            tr2.add(TripRow("Rest needed in", formatDuration(s.nextRestMin) + " (sim)", if (s.nextRestMin <= 30f) red else if (s.nextRestMin <= 90f) amber else white))
        }
        if (place.isNotEmpty()) tr2.add(TripRow("Location", place.removePrefix("Near ")))
        cards.add(TripCard("TIME & REST", tr2))

        // ---- dashboard layout for wide screens (mirrors the SimHub "ETS2 overlay" design)
        val us = java.util.Locale.US
        val green = Color.rgb(60, 220, 130)
        val nowMs = System.currentTimeMillis()
        val calNow = java.util.Calendar.getInstance()
        fun durShort(min: Float): String {
            val m = Math.round(Math.abs(min))
            return when {
                m >= 1440 -> "${m / 1440}d ${"%02d".format((m % 1440) / 60)}h"
                m >= 60 -> "${m / 60}h ${"%02d".format(m % 60)}m"
                else -> "${m}m"
            }
        }
        val gameNow = s.gameTimeMin
        val arrAbs = if (simMin >= 0f && gameNow >= 0f) gameNow + simMin else -1f
        val arrDays = if (arrAbs >= 0f) (arrAbs / 1440f).toInt() - (gameNow / 1440f).toInt() else 0
        val arrBig = if (arrAbs >= 0f) clockText(arrAbs).substring(4) else "-"
        val arrSub = if (arrAbs >= 0f) clockText(arrAbs).substring(0, 3).uppercase() + (if (arrDays > 0) " +${arrDays}d" else "") else ""
        var etaYours = "-"
        if (realMin >= 0f) {
            val c2 = java.util.Calendar.getInstance()
            c2.add(java.util.Calendar.MINUTE, Math.round(realMin))
            val off = c2.timeZone.getOffset(c2.timeInMillis)
            val dayDiff = ((c2.timeInMillis + off) / 86_400_000L) - ((nowMs + off) / 86_400_000L)
            etaYours = String.format(us, "%02d:%02d", c2.get(java.util.Calendar.HOUR_OF_DAY), c2.get(java.util.Calendar.MINUTE)) + (if (dayDiff > 0) " +${dayDiff}d" else "")
        }
        val dueMin = j?.dueMin ?: -1f
        val late = dueMin >= 0f && simMin >= 0f && simMin > dueMin
        val spare = if (dueMin >= 0f && simMin >= 0f) dueMin - simMin else null
        val avgNeeded = if (dueMin > 0f && remGameKm > 0f) remGameKm / (dueMin / 60f) else -1f
        val fr = if (dt != null && dt.fuelCapL > 0f) dt.fuelL / dt.fuelCapL else -1f
        val needL = if (dt != null) remGameKm * dt.consumptionLPerKm else 0f
        val atArrival = if (dt != null) dt.fuelL - needL else 0f
        val planned = j?.plannedKm ?: 0
        val market = (dt?.jobMarket ?: "").replace('_', ' ').uppercase().ifEmpty { "-" }
        val row1 = listOf(
            DashCard("CARGO", j?.cargo?.ifEmpty { "-" } ?: "-", white, "", "", green, listOf(
                TripRow("Mass", if (j != null && j.massKg > 0f) String.format(us, "%.1f t", j.massKg / 1000f) else "-"),
                TripRow("Units", (j?.units ?: 0).toString()),
                TripRow("Cargo damage", pct(dt?.cargoDamage ?: 0f), if ((dt?.cargoDamage ?: 0f) < 0.5f) green else TripScreen.pctColor(dt?.cargoDamage ?: 0f)),
                TripRow("Trailer", dt?.trailerName?.ifEmpty { "-" } ?: "-"),
            )),
            DashCard("ARRIVAL (ETS2 CLOCK)", arrBig, white, arrSub, "", green, listOf(
                TripRow("ETA (your clock)", etaYours),
                TripRow("Time left (game)", if (simMin >= 0f) durShort(simMin) else "-"),
                TripRow("Time left (real)", if (realMin >= 0f) durShort(realMin) else "-"),
                TripRow("Distance left", String.format(us, "%,.0f km", remGameKm)),
            )),
            DashCard("TIME REMAINING TO DEADLINE", if (dueMin >= 0f) durShort(dueMin) else "-", white, "",
                if (dueMin < 0f) "" else if (late) "LATE" else "ON TIME", if (late) red else green, listOf(
                    TripRow("Deadline", if (dueMin >= 0f && gameNow >= 0f) clockText(gameNow + dueMin).uppercase() else "-"),
                    TripRow("ETA", if (arrAbs >= 0f) clockText(arrAbs).uppercase() else "-"),
                    TripRow("Margin", if (spare != null) (if (spare >= 0f) "+" else "-") + durShort(spare) else "-", if (spare != null && spare < 0f) red else green),
                    TripRow("Avg speed needed", if (avgNeeded >= 0f) String.format(us, "%.0f km/h", avgNeeded) else "-"),
                )),
            DashCard("FUEL FOR THIS TRIP", if (fr >= 0f) String.format(us, "%.0f%%", fr * 100f) else "-",
                if (fr < 0.12f) red else if (fr < 0.25f) amber else green,
                if (dt != null) String.format(us, "%.0f / %.0f L", dt.fuelL, dt.fuelCapL) else "",
                if (dt == null || remGameKm <= 0f) "" else if (atArrival < 0f) "REFUEL NEEDED" else "ENOUGH FUEL",
                if (atArrival < 0f) red else green, listOf(
                    TripRow("Range", if (dt != null) String.format(us, "%,.0f km", dt.rangeKm) else "-"),
                    TripRow("Consumption", if (dt != null) String.format(us, "%.1f L/100km", dt.consumptionLPerKm * 100f) else "-"),
                    TripRow("Needed for trip", String.format(us, "%.0f L", needL)),
                    TripRow("Fuel at arrival", String.format(us, "%.0f L", atArrival), if (atArrival < 0f) red else white),
                )),
            DashCard("JOB & INCOME", if (j != null && j.income > 0) "\u20ac " + String.format(us, "%,d", j.income) else "-", white, "", "", green, listOf(
                TripRow("Planned distance", if (planned > 0) String.format(us, "%,d km", planned) else "-"),
                TripRow("Income rate", if (j != null && planned > 0 && j.income > 0) "\u20ac " + String.format(us, "%.2f/km", j.income.toFloat() / planned) else "-"),
                TripRow("Job market", market),
                TripRow("Special job", if (market.contains("SPECIAL")) "YES" else "NO"),
            )),
        )
        val order = listOf("THIS TRIP", "TRAILER & CARGO", "TRUCK", "ROUTE", "TIME & REST")
        val row2 = order.mapNotNull { t -> cards.firstOrNull { it.title == t } }
        val progress = if (planned > 0 && remGameKm > 0f) (1f - remGameKm / planned).coerceIn(0f, 1f) else 0f
        val dash = DashData(
            fromCity = j?.srcCity ?: "-", fromCompany = j?.srcCompany ?: "",
            toCity = j?.destCity ?: (if (hasDest) destName else "-"), toCompany = j?.destCompany ?: "",
            leftText = String.format(us, "%,.0f km left", remGameKm),
            ofText = if (planned > 0) String.format(us, "of %,d km  \u00b7  %.0f%%", planned, progress * 100f) else "",
            progress = progress,
            local = String.format(us, "%02d:%02d:%02d", calNow.get(java.util.Calendar.HOUR_OF_DAY), calNow.get(java.util.Calendar.MINUTE), calNow.get(java.util.Calendar.SECOND)),
            sim = clockText(gameNow).uppercase(),
            onJob = j != null,
            row1 = row1, row2 = row2,
        )
        tripScreen.setCards(cards, dash)
    }

    private fun updateAltitude(s: TruckState) {
        elev.add(motion.travelled, s.y)
        val g = motion.gradientPct
        val arrow = if (!motion.gradientValid || Math.abs(g) < 0.5f) "\u25AC" else if (g > 0) "\u25B2" else "\u25BC"
        val gs = if (motion.gradientValid) String.format(java.util.Locale.US, "%s %.1f%%", arrow, Math.abs(g)) else arrow
        altText.text = "$gs\n${Math.round(s.y)} m"
        altText.setTextColor(when {
            motion.gradientValid && g <= -5f -> Color.rgb(255, 170, 90)
            motion.gradientValid && g >= 6f -> Color.rgb(255, 120, 120)
            else -> Color.WHITE
        })
    }

    /** Spoken warnings for sustained steep descents (engine brake) and climbs. */
    private fun checkHill(s: TruckState) {
        if (!motion.gradientValid || s.speedKmh < 25f) { descStart = -1f; climbStart = -1f; return }
        val g = motion.gradientPct
        val t = motion.travelled
        val now = SystemClock.elapsedRealtime()
        if (g <= -5f) {
            if (descStart < 0f) descStart = t
            if (t - descStart >= 120f && now - lastDescWarn > 90_000L) {
                lastDescWarn = now
                voice.say("Steep descent. Use the engine brake.")
                toast("Steep descent - engine brake")
            }
        } else if (g > -3f) descStart = -1f
        if (g >= 6f) {
            if (climbStart < 0f) climbStart = t
            if (t - climbStart >= 150f && now - lastClimbWarn > 120_000L) {
                lastClimbWarn = now
                voice.say("Steep climb. Select a lower gear.")
            }
        } else if (g < 4f) climbStart = -1f
    }

    private fun showElevation() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), 0) }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until elev.size) { lo = minOf(lo, elev.alt[i]); hi = maxOf(hi, elev.alt[i]) }
        val summary = if (elev.size < 2) "No data yet" else String.format(
            java.util.Locale.US, "Now %.0f m  \u00b7  climbed %.0f m  \u00b7  descended %.0f m  \u00b7  range %.0f to %.0f m",
            truck?.y ?: 0f, elev.ascent(), elev.descent(), lo, hi)
        box.addView(TextView(this).apply { text = summary; textSize = 13f; setPadding(0, 0, 0, dp(8)) })
        box.addView(ElevationChart(this, elev, distScale))
        AlertDialog.Builder(this).setTitle("Trip elevation").setView(box)
            .setPositiveButton("Close", null)
            .setNeutralButton("Reset trip") { _, _ -> elev.clear() }
            .show()
    }

    private fun checkAlerts(s: TruckState) {
        if (map == null) return
        val low = s.fuelWarning || (s.fuelFraction in 0f..0.12f)
        if (low && !fuelAlerted) {
            fuelAlerted = true
            val h = nearestPois(Poi.FUEL, 1).firstOrNull()
            voice.say("Low fuel." + (if (h != null) " Nearest fuel station is ${nav?.spokenDistance(h.dist) ?: ""} ${direction(h)}." else ""))
            toast("Low fuel - tap POI, then Fuel station")
        }
        if (!low && s.fuelFraction > 0.25f) fuelAlerted = false
        val r = s.nextRestMin
        if (r >= 0f) {
            if (r > 60f) { rest30 = false; rest10 = false }
            val mins = if (r <= 10f) 10 else 30
            if ((r <= 10f && !rest10) || (r <= 30f && !rest30)) {
                if (r <= 10f) rest10 = true
                rest30 = true
                val h = nearestPois(Poi.PARKING, 1).firstOrNull()
                voice.say("You will need a rest in about $mins minutes." +
                    (if (h != null) " Nearest rest area is ${nav?.spokenDistance(h.dist) ?: ""} ${direction(h)}." else ""))
            }
        }
    }

    private fun updatePlace(now: Long) {
        if (now - lastPlaceCheck < 2000L) return
        lastPlaceCheck = now
        val m = map ?: return
        var best: City? = null
        var bd = 4000f
        for (c in m.cities) {
            val dx = c.x - motion.x; val dz = c.z - motion.z
            if (dx > bd || dx < -bd || dz > bd || dz < -bd) continue
            val d = hypot(dx, dz)
            if (d < bd) { bd = d; best = c }
        }
        place = if (best != null) "Near ${best.name}" else ""
    }

    private fun confirmDestination(x: Float, z: Float) {
        val canStop = hasDest && nav?.active == true
        val items = if (canStop) arrayOf("Navigate here", "Add as waypoint", "Avoid road here") else arrayOf("Navigate here", "Avoid road here")
        AlertDialog.Builder(this)
            .setTitle("This point on the map")
            .setItems(items) { _, i ->
                when (items[i]) {
                    "Navigate here" -> computeRoute(x, z, "Selected point", announce = true)
                    "Add as waypoint" -> addWaypoint(x, z, "Selected point")
                    else -> avoidRoadAt(x, z)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------------- waypoints --

    // ---------------------------------------------------------------------- saved trips --

    private class SavedTrip(val name: String, val mode: Int, val dest: Stop, val stops: List<Stop>, val lengthM: Float)

    private fun stopJson(s: Stop) = org.json.JSONObject().put("x", s.x.toDouble()).put("z", s.z.toDouble()).put("n", s.name)
    private fun stopFrom(o: org.json.JSONObject) = Stop(o.getDouble("x").toFloat(), o.getDouble("z").toFloat(), o.optString("n", "Stop"))

    private fun loadTrips(): MutableList<SavedTrip> {
        val out = ArrayList<SavedTrip>()
        try {
            val arr = org.json.JSONArray(prefs.getString("trips", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = o.getJSONArray("stops")
                out.add(SavedTrip(o.getString("name"), o.optInt("mode", 0), stopFrom(o.getJSONObject("dest")),
                    (0 until st.length()).map { stopFrom(st.getJSONObject(it)) }, o.optDouble("len", 0.0).toFloat()))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun storeTrips(list: List<SavedTrip>) {
        val arr = org.json.JSONArray()
        for (t in list) arr.put(org.json.JSONObject().put("name", t.name).put("mode", t.mode).put("dest", stopJson(t.dest))
            .put("stops", org.json.JSONArray().also { a -> t.stops.forEach { a.put(stopJson(it)) } }).put("len", t.lengthM.toDouble()))
        prefs.edit().putString("trips", arr.toString()).apply()
    }

    /** Saves the planned trip (destination, waypoints and route type) under a name the user can edit. */
    private fun saveCurrentTrip() {
        if (!hasDest || nav?.active != true) { toast("Plan a route first, then save it"); return }
        if (destIsPoi) { toast("Finish the POI detour first, then save the trip"); return }
        val input = EditText(this).apply { setText("$startLabel \u2192 $destName"); setSingleLine(); selectAll() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Save this trip as").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "$startLabel \u2192 $destName" }
                val list = loadTrips()
                list.removeAll { it.name == name } // same name = replace
                list.add(0, SavedTrip(name, routeMode.ordinal, Stop(destX, destZ, destName), ArrayList(waypoints), routeRemainingM.coerceAtLeast(0f)))
                storeTrips(list)
                toast("Trip saved: $name")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showTrips() {
        val list = loadTrips()
        val b = AlertDialog.Builder(this).setTitle("Saved trips")
            .setPositiveButton("Save current trip") { _, _ -> saveCurrentTrip() }
            .setNegativeButton("Close", null)
        if (list.isEmpty()) {
            b.setMessage("No saved trips yet. Plan a route (and any waypoints), then tap Save current trip.")
        } else {
            val rows = list.map { t ->
                val km = t.lengthM * distScale / 1000f
                val mode = RouteMode.values().getOrNull(t.mode)?.label ?: ""
                "${t.name}\n${if (km >= 1f) String.format(java.util.Locale.US, "%.0f km", km) + " \u00b7 " else ""}" +
                    "${t.stops.size} waypoint${if (t.stops.size == 1) "" else "s"} \u00b7 $mode"
            }
            b.setItems(rows.toTypedArray()) { _, i -> tripOptions(list, i) }
        }
        b.show()
    }

    private fun tripOptions(list: MutableList<SavedTrip>, i: Int) {
        val t = list.getOrNull(i) ?: return
        AlertDialog.Builder(this).setTitle(t.name)
            .setItems(arrayOf("Start this trip from here", "Rename", "Delete")) { _, k ->
                when (k) {
                    0 -> startSavedTrip(t)
                    1 -> renameTrip(list, i)
                    else -> { list.removeAt(i); storeTrips(list); toast("Deleted: ${t.name}") }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameTrip(list: MutableList<SavedTrip>, i: Int) {
        val t = list.getOrNull(i) ?: return
        val input = EditText(this).apply { setText(t.name); setSingleLine(); selectAll() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Rename trip").setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == t.name) return@setPositiveButton
                list.removeAll { it.name == name } // an existing trip with that name is replaced
                val at = list.indexOfFirst { it === t }.coerceAtLeast(0)
                list[at] = SavedTrip(name, t.mode, t.dest, t.stops, t.lengthM)
                storeTrips(list)
                toast("Renamed to: $name")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startSavedTrip(t: SavedTrip) {
        if (map == null) { toast("Map not loaded yet"); return }
        if (truck?.connected != true) { toast("Start the game first - no truck position yet"); return }
        if (routing.get()) { toast("Still planning - try again in a moment"); return }
        RouteMode.values().getOrNull(t.mode)?.let { routeMode = it; updateModeButton() }
        waypoints.clear(); waypoints.addAll(t.stops); resumeStops.clear()
        computeRoute(t.dest.x, t.dest.z, t.dest.name, announce = true, keepStops = true)
    }

    // ----------------------------------------------------------------- lane panel + PC overlay --

    private val laneNet = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastLaneJson = ""
    private var lastLanePush = 0L

    /** Shows the lane panel and relays it to the companion so the PC lane overlay can display it as well. */
    private fun setLaneUi(lanes: List<Lane>?, caption: String = "") {
        laneView.setLanes(lanes, caption)
        val arr = org.json.JSONArray()
        if (lanes != null) for (l in lanes) {
            arr.put(org.json.JSONObject().put("a", if (l.angleDeg.isNaN()) org.json.JSONObject.NULL else Math.round(l.angleDeg * 10) / 10.0)
                .put("p", l.preferred).put("alt", l.altDeg?.let { Math.round(it * 10) / 10.0 } ?: org.json.JSONObject.NULL))
        }
        val body = org.json.JSONObject().put("lanes", if (lanes == null) org.json.JSONObject.NULL else arr).put("caption", caption).toString()
        val now = SystemClock.elapsedRealtime()
        if (body == lastLaneJson && now - lastLanePush < 2000L) return
        if (lanes != null && body != lastLaneJson && now - lastLanePush < 400L) return
        lastLaneJson = body; lastLanePush = now
        val h = host
        if (h.isEmpty()) return
        laneNet.execute {
            try {
                val c = java.net.URL("http://$h:31378/lanes").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 800; c.readTimeout = 800; c.requestMethod = "POST"; c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) { }
        }
    }

    private fun syncStops() {
        mapView.stops = FloatArray(waypoints.size * 2) { if (it % 2 == 0) waypoints[it / 2].x else waypoints[it / 2].z }
        mapView.invalidate()
    }

    private fun addWaypoint(x: Float, z: Float, name: String) {
        if (!hasDest) { toast("Create a route first, then add waypoints"); return }
        if (routing.get()) { toast("Still planning - try again in a moment"); return }
        waypoints.add(Stop(x, z, name))
        syncStops()
        toast("Waypoint ${waypoints.size} added: $name")
        voice.say("Waypoint added: $name")
        rerouteForModeChange()
    }

    private fun showStops() {
        if (!hasDest) { toast("Create a route with Search first, then add waypoints here"); return }
        val names = waypoints.mapIndexed { i, w -> "${i + 1}. ${w.name}" } + "Destination: $destName"
        AlertDialog.Builder(this)
            .setTitle("Route stops")
            .setItems(names.toTypedArray()) { _, i -> if (i < waypoints.size) stopOptions(i) }
            .setPositiveButton("Add waypoint") { _, _ -> showSearch(true) }
            .setNeutralButton("Clear waypoints") { _, _ -> waypoints.clear(); syncStops(); rerouteForModeChange() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun stopOptions(i: Int) {
        val w = waypoints.getOrNull(i) ?: return
        val items = arrayOf("Remove", "Move earlier", "Move later")
        AlertDialog.Builder(this)
            .setTitle(w.name)
            .setItems(items) { _, k ->
                when (k) {
                    0 -> waypoints.removeAt(i)
                    1 -> if (i > 0) { waypoints.removeAt(i); waypoints.add(i - 1, w) }
                    2 -> if (i < waypoints.size - 1) { waypoints.removeAt(i); waypoints.add(i + 1, w) }
                }
                syncStops()
                rerouteForModeChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // roads the user has found blocked (invisible barriers, closures) are remembered and routed around
    private fun loadAvoid() {
        avoidPoints.clear()
        for (part in (prefs.getString("avoid", "") ?: "").split(";")) {
            val xy = part.split(",")
            if (xy.size == 2) {
                val x = xy[0].toFloatOrNull(); val z = xy[1].toFloatOrNull()
                if (x != null && z != null) avoidPoints.add(Pair(x, z))
            }
        }
    }

    private fun saveAvoid() {
        prefs.edit().putString("avoid", avoidPoints.joinToString(";") { "${it.first},${it.second}" }).apply()
    }

    private fun avoidRoadAt(x: Float, z: Float) {
        avoidPoints.add(Pair(x, z))
        saveAvoid()
        router?.setAvoid(avoidPoints)
        toast("Avoiding the road here (${avoidPoints.size} avoided in total)")
        voice.say("Avoiding that road")
        rerouteForModeChange()
    }

    private fun clearAvoid() {
        avoidPoints.clear()
        saveAvoid()
        router?.setAvoid(avoidPoints)
        toast("Avoided roads cleared")
        rerouteForModeChange()
    }

    // ---------------------------------------------------------------------------- dialogs --

    private fun showSearch(asWaypoint: Boolean = false) {
        val m = map
        if (m == null) { toast("Map not loaded yet"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val input = EditText(this).apply {
            hint = "City name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val list = ListView(this)
        box.addView(input)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        var shown: List<City> = m.cities
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        fun refresh(q: String) {
            val key = City.normalize(q.trim())
            shown = if (key.isEmpty()) m.cities else m.cities.filter { it.key.contains(key) }
                .sortedBy { if (it.key.startsWith(key)) 0 else 1 }
            adapter.clear()
            adapter.addAll(shown.take(200).map { it.name })
            adapter.notifyDataSetChanged()
        }
        refresh("")
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })
        val dlg = AlertDialog.Builder(this).setTitle(if (asWaypoint) "Add waypoint" else "Navigate to").setView(box)
            .setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = shown.getOrNull(pos)
            if (c != null) {
                dlg.dismiss()
                if (asWaypoint) addWaypoint(c.x, c.z, c.name)
                else if (hasDest && nav?.active == true) {
                    AlertDialog.Builder(this).setTitle(c.name)
                        .setItems(arrayOf("Add as waypoint", "New destination (replace route)")) { _, k ->
                            if (k == 0) addWaypoint(c.x, c.z, c.name) else computeRoute(c.x, c.z, c.name, announce = true)
                        }
                        .setNegativeButton("Cancel", null).show()
                } else computeRoute(c.x, c.z, c.name, announce = true)
            }
        }
        dlg.show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val hostEdit = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.20"
            setText(host); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val scaleEdit = EditText(this).apply {
            hint = "Distance scale (game shows map x19)"
            setText(distScale.toString()); setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val voiceBox = CheckBox(this).apply { text = "Voice guidance"; isChecked = voice.enabled }
        val help = TextView(this).apply {
            text = "Telemetry comes from the TruckSim GPS server (port 31377). The map comes from the " +
                "companion script on your PC (port 31378). Distances are shown in game kilometres " +
                "(map metres x scale); set the scale to 1 to see raw map metres."
            textSize = 12f
        }
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modeText = arrayOf(
            "Heavy transport (default): motorways and major roads, avoids small roads and awkward junctions",
            "Fastest: shortest travel time on any road",
            "Tourist: prefers smaller, scenic roads and avoids motorways",
        )
        for ((i, rm) in RouteMode.values().withIndex()) {
            modeGroup.addView(RadioButton(this).apply { text = modeText[i]; id = 2000 + i; isChecked = (routeMode == rm) })
        }
        val outGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val outNames = arrayOf("Speak on the tablet", "Speak on the PC (headset, via companion)", "Speak on both")
        for ((i, n) in outNames.withIndex()) {
            outGroup.addView(RadioButton(this).apply { text = n; id = 1000 + i; isChecked = (voice.output == i) })
        }
        val testBtn = button("Test voice") {
            voice.output = outGroup.checkedRadioButtonId - 1000

            voiceProblemShown = false
            toast("Voice status: ${voice.status}")
            voice.test()
        }
        box.addView(TextView(this).apply { text = "Route type"; textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        box.addView(modeGroup)
        box.addView(button("Clear avoided roads (${avoidPoints.size})") { clearAvoid() })
        box.addView(hostEdit); box.addView(scaleEdit); box.addView(voiceBox); box.addView(outGroup); box.addView(testBtn); box.addView(help)
        fun save() {
            host = hostEdit.text.toString()
            val sc = scaleEdit.text.toString().toFloatOrNull()
            if (sc != null && sc > 0f) { distScale = sc; nav?.distScale = sc }
            voice.enabled = voiceBox.isChecked
            val msel = modeGroup.checkedRadioButtonId - 2000
            if (msel in RouteMode.values().indices && RouteMode.values()[msel] != routeMode) {
                routeMode = RouteMode.values()[msel]
                rerouteForModeChange()
            }
            val sel = outGroup.checkedRadioButtonId - 1000
            if (sel in 0..2) { voice.output = sel; prefs.edit().putInt("voice_out", sel).apply() }
            updateVoiceButton()
            prefs.edit().putBoolean("voice", voiceBox.isChecked).apply()
            telemetry.stop(); telemetry.start()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Settings").setView(scroll)
            .setPositiveButton("Save") { _, _ -> save() }
            .setNeutralButton("Save + download map") { _, _ -> save(); downloadMap() }
            .setNegativeButton("Cancel", null).show()
    }
}
