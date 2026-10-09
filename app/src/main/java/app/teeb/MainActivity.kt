package app.teeb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private var web: WebView? = null
    private lateinit var sp: SharedPreferences

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#060912")
        window.navigationBarColor = Color.parseColor("#060912")
        sp = getSharedPreferences("teeb", 0)
        val url = sp.getString("url", null)
        if (url == null) askUrl() else start(url)
    }

    private fun askUrl() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(60, 160, 60, 60)
        box.setBackgroundColor(Color.parseColor("#060912"))
        val t = TextView(this); t.text = "TEEB"; t.textSize = 34f; t.setTextColor(Color.parseColor("#4da3ff"))
        val h = TextView(this); h.text = "Paste your TEEB web address once (your .pages.dev link)."; h.setTextColor(Color.parseColor("#9fb0d0")); h.setPadding(0, 20, 0, 20)
        val e = EditText(this); e.hint = "yourname.pages.dev"; e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY)
        val bt = Button(this); bt.text = "Start TEEB"
        bt.setOnClickListener {
            var u = e.text.toString().trim()
            if (u.isNotEmpty()) {
                if (!u.startsWith("http")) u = "https://$u"
                u = u.trimEnd('/')
                sp.edit().putString("url", u).apply()
                start(u)
            }
        }
        box.addView(t); box.addView(h); box.addView(e); box.addView(bt)
        setContentView(box)
    }

    private fun start(url: String) {
        val w = WebView(this)
        web = w
        w.setBackgroundColor(Color.parseColor("#060912"))
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) { runOnUiThread { r.grant(r.resources) } }
        }
        w.addJavascriptInterface(Bridge(), "TEEBNative")
        setContentView(w)
        w.loadUrl(url)
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(need.toTypedArray(), 1) else powers()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { powers() }

    private fun powers() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startForegroundService(Intent(this, ListenService::class.java))
        if (ControlService.inst == null && !sp.getBoolean("asked", false)) {
            sp.edit().putBoolean("asked", true).apply()
            AlertDialog.Builder(this).setTitle("Let TEEB control apps")
                .setMessage("Open Accessibility, tap TEEB, and switch it on. This lets TEEB open apps, tap, scroll and type when you ask.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Later", null).show()
        }
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface
        fun act(cmd: String): String = Control.run(this@MainActivity, cmd) ?: "I can't do that one."
    }
}
