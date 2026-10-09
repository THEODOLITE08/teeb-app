package app.teeb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class ListenService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var armedUntil = 0L
    private var alive = true
    private var wl: PowerManager.WakeLock? = null
    private val wake = Regex("\\b(teeb|beats|tib|tebe)\\b")
    private val hist = ArrayList<Pair<String, String>>()

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("teeb", "TEEB listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "teeb").setContentTitle("TEEB is listening").setContentText("Say TEEB, then ask")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teeb:listen")
        wl?.acquire()
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { h.post { listen() } }
                @Deprecated("old signature") override fun onError(id: String?) { h.post { listen() } }
            })
        }
        h.postDelayed({ listen() }, 800)
    }

    private fun again(ms: Long) { h.postDelayed({ listen() }, ms) }

    private fun listen() {
        if (!alive) return
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { again(10000); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) again(300) else handle(t)
            }
            override fun onError(e: Int) {
                again(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 300 else 1500)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }

    private fun handle(text: String) {
        val s = text.lowercase().trim()
        var cmd: String? = null
        val m = wake.find(s)
        if (m != null) cmd = s.substring(m.range.last + 1).trim().trimStart(',', '.', ' ')
        else if (System.currentTimeMillis() < armedUntil) cmd = s
        if (cmd == null) { again(200); return }
        if (cmd.isEmpty()) { armedUntil = System.currentTimeMillis() + 8000; say("Yes?"); return }
        armedUntil = System.currentTimeMillis() + 15000
        val c = Control.run(this, cmd)
        if (c != null) say(c) else ai(cmd)
    }

    private fun ai(q: String) {
        val site = getSharedPreferences("teeb", 0).getString("url", "") ?: ""
        Thread {
            var out = "I couldn't reach my brain. Check the connection."
            try {
                val sys = "You are TEEB, a warm, playful, respectful voice assistant. Reply in one to three short spoken sentences with no markdown. Local time: ${Date()}."
                val m = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                for ((a, b) in hist) {
                    m.put(JSONObject().put("role", "user").put("content", a))
                    m.put(JSONObject().put("role", "assistant").put("content", b))
                }
                m.put(JSONObject().put("role", "user").put("content", q))
                val c = URL("$site/api/ai").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("messages", m).toString().toByteArray()) }
                val body = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
                val j = JSONObject(body)
                val t = j.optString("text")
                out = if (t.isNotBlank()) t else j.optString("error", out)
                if (t.isNotBlank()) { hist.add(Pair(q, t)); if (hist.size > 5) hist.removeAt(0) }
            } catch (e: Exception) { }
            h.post { say(out) }
        }.start()
    }

    private fun say(t: String) {
        sr?.destroy(); sr = null
        if (ttsReady) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u") else again(500)
    }

    override fun onDestroy() {
        alive = false
        sr?.destroy(); tts?.shutdown(); wl?.release()
        super.onDestroy()
    }
}
