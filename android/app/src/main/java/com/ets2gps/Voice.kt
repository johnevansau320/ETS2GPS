package com.ets2gps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Text-to-speech for guidance prompts. Plays as navigation guidance, briefly ducks other audio,
 * and reports problems (no engine, no language data) instead of failing silently.
 */
class Voice(ctx: Context) {
    private val app = ctx.applicationContext
    private var ready = false
    private var failed: String? = null
    private val pending = ArrayList<String>()
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: Any? = null

    var enabled = true

    /** 0 = tablet speaker, 1 = PC (via the companion, e.g. your headset), 2 = both. */
    var output = 0

    /** Host name / IP of the PC running the companion. */
    var pcHost: () -> String = { "" }

    /** Called with short notices such as "PC speaker unreachable, using the tablet". */
    var onNotice: ((String) -> Unit)? = null
    private val net = Executors.newSingleThreadExecutor()
    private var pcWarned = false
    var rate = 1.0f
        set(v) { field = v; if (ready) tts.setSpeechRate(v) }

    /** Called (on an arbitrary thread) when speech cannot work, with a human-readable reason. */
    var onProblem: ((String) -> Unit)? = null

    val status: String
        get() = when {
            failed != null -> failed!!
            ready -> "ready"
            else -> "starting"
        }

    private val tts: TextToSpeech = TextToSpeech(app) { st -> onEngineInit(st) }

    private fun onEngineInit(st: Int) {
        if (st != TextToSpeech.SUCCESS) {
            fail("No text-to-speech engine could be started. Install one (for example Google Speech Services) in Android settings.")
        } else {
            var r = tts.setLanguage(Locale.getDefault())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = tts.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                fail("The text-to-speech voice data is missing. Install English voice data in Android settings.")
            } else {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                tts.setSpeechRate(rate)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { abandonFocus() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { abandonFocus() }
                })
                ready = true
                val queued = ArrayList(pending)
                pending.clear()
                queued.lastOrNull()?.let { say(it) }
            }
        }
    }

    private fun fail(reason: String) {
        failed = reason
        pending.clear()
        if (output != 1) onProblem?.invoke(reason)
    }

    /** Speak [text]; newer prompts replace anything still queued (stale directions are worse than none). */
    fun say(text: String) {
        if (!enabled) return
        if (output != 0) sendToPc(text)
        if (output != 1) sayLocal(text)
    }

    private fun sendToPc(text: String) {
        net.execute {
            var ok = false
            try {
                val host = pcHost().substringBefore(":")
                if (host.isNotEmpty()) {
                    val c = URL("http://$host:31378/say").openConnection() as HttpURLConnection
                    c.connectTimeout = 1500
                    c.readTimeout = 2500
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ok = c.responseCode == 200
                    c.disconnect()
                }
            } catch (_: Exception) {
            }
            if (ok) {
                pcWarned = false
            } else {
                if (!pcWarned) { pcWarned = true; onNotice?.invoke("PC speaker unreachable - speaking on the tablet instead") }
                if (output == 1) sayLocal(text)
            }
        }
    }

    private fun sayLocal(text: String) {
        if (failed != null) { if (output != 1) onProblem?.invoke(failed!!); return }
        if (!ready) { pending.add(text); return }
        requestFocus()
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
        if (r != TextToSpeech.SUCCESS) {
            abandonFocus()
            onProblem?.invoke("Text-to-speech refused to speak (error $r)")
        }
    }

    fun test() {
        val was = enabled
        enabled = true
        say("Voice guidance is working. In 500 meters, turn right towards London.")
        enabled = was
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    ).build()
                focusRequest = req
                audio.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                (focusRequest as? AudioFocusRequest)?.let { audio.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
