package com.kjt7942.carassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.location.Location
import android.bluetooth.BluetoothAdapter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.InputDevice
import android.view.KeyEvent
import android.media.ToneGenerator
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 차량 비서: WebView(화면) + 네이티브 기능(GPS, 음성 인식, 음성 출력, 자동 업데이트)을 연결하는 단일 화면 앱.
 * 화면은 assets/index.html(또는 내려받은 최신 화면), 네이티브 기능은 Android 객체(Bridge)로 호출한다.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newSingleThreadExecutor()
    private val aiExec = Executors.newSingleThreadExecutor()
    private var keyProbe = false
    private var mediaSession: MediaSession? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsKo = -99

    private var recognizer: SpeechRecognizer? = null

    private var locMgr: LocationManager? = null
    private var locListener: LocationListener? = null
    private var lastLoc: Location? = null
    private var lastLocAt = 0L

    // ---- 업데이트 상태 ----
    private val versionUrl = "https://github.com/kjt7942/carAssistant/releases/download/latest/version.json"
    private val actionInstall = "com.kjt7942.carassistant.INSTALL_STATUS"
    private var uiLoadId = 0
    private var uiOkFlag = false
    private var curUi = 0
    private var uiSource = "내장"
    private var updState = "대기"
    private var lastCheckAt = 0L
    private var apkFile: File? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkUpdates(false)
            main.postDelayed(this, 3L * 60 * 1000)
        }
    }

    private val reqCode = 7
    private val perms: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF05070A.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        loadUi()

        val filter = IntentFilter(actionInstall)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(installReceiver, filter)
        }

        abandonFocus() // 앱이 볼륨을 낮춘 채로 종료됐다면 원래 볼륨으로 복구
        initTts()
        if (!hasAll()) requestPermissions(perms, reqCode)
        main.postDelayed(checkRunnable, 5000)
        main.postDelayed({ emitIntent(intent) }, 1800)
    }

    private fun fullscreenOn(): Boolean = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("full", true)

    /** 상태줄·내비게이션 막대를 숨기고 화면 전체를 쓴다. */
    @Suppress("DEPRECATION")
    private fun applyFullscreen() {
        val dv = window.decorView
        if (fullscreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            dv.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            dv.setOnSystemUiVisibilityChangeListener { v ->
                if (fullscreenOn() && (v and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    main.postDelayed({ applyFullscreen() }, 1500)
                }
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            dv.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            dv.setOnSystemUiVisibilityChangeListener(null)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        emitIntent(intent)
    }

    /** 음성 비서 호출 신호(핸들 음성 버튼 등)로 열렸는지 화면에 알린다. */
    private fun emitIntent(i: Intent?) {
        val a = i?.action ?: return
        if (a == Intent.ACTION_MAIN) return
        js("onNativeIntent", JSONObject().put("action", a).put("cats", (i.categories ?: emptySet<String>()).joinToString(",")).put("type", i.type ?: "").put("from", i.`package` ?: ""))
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen()
        startLocation()
        if (System.currentTimeMillis() - lastCheckAt > 30L * 1000 && lastCheckAt != 0L) {
            checkUpdates(false)
        }
    }

    override fun onPause() {
        setKeyProbe(false)
        abandonFocus()
        stopLocation()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocation()
        main.removeCallbacks(checkRunnable)
        try {
            unregisterReceiver(installReceiver)
        } catch (e: Exception) {
        }
        abandonFocus()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
        js("onNativePerms", JSONObject().put("ok", hasLocation()))
    }

    // ---------- 권한 ----------

    private fun granted(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasAll(): Boolean =
        hasLocation() && granted(Manifest.permission.RECORD_AUDIO)

    // ---------- 화면(JS) 호출 ----------

    private fun js(fn: String, obj: JSONObject) {
        main.post {
            web.evaluateJavascript("window.$fn && window.$fn(${obj})", null)
        }
    }

    // ---------- 화면 불러오기 (내려받은 최신 화면이 있으면 그것을, 없으면 내장 화면) ----------

    private fun metaInt(html: String, name: String, def: Int): Int {
        val m = Regex("name=\"$name\" content=\"(\\d+)\"").find(html)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: def
    }

    private fun uiBuildOf(html: String): Int = metaInt(html, "ui-build", 0)

    private fun minNativeOf(html: String): Int = metaInt(html, "min-native", 1)

    private fun installedVersionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        1
    }

    private fun uiFile(): File = File(File(filesDir, "ui"), "index.html")

    private fun loadUi() {
        val bundled = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        var html = bundled
        uiSource = "내장"
        curUi = uiBuildOf(bundled)
        val f = uiFile()
        if (f.exists()) {
            try {
                val o = f.readText()
                if (uiBuildOf(o) > curUi && minNativeOf(o) <= installedVersionCode()) {
                    html = o
                    uiSource = "내려받음"
                    curUi = uiBuildOf(o)
                } else {
                    f.delete()
                }
            } catch (e: Exception) {
                f.delete()
            }
        }
        uiOkFlag = false
        uiLoadId += 1
        val myId = uiLoadId
        val fromDownload = uiSource == "내려받음"
        val buildNo = curUi
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        if (fromDownload) {
            // 내려받은 화면이 15초 안에 정상 시작 신호를 못 주면 버리고 내장 화면으로 되돌린다.
            main.postDelayed({
                if (myId == uiLoadId && !uiOkFlag) {
                    getSharedPreferences("upd", MODE_PRIVATE).edit().putInt("badUi", buildNo).apply()
                    f.delete()
                    loadUi()
                }
            }, 15000)
        }
    }

    // ---------- 자동 업데이트 ----------

    private fun upd(state: String, msg: String, version: Int) {
        js(
            "onNativeUpdate",
            JSONObject().put("state", state).put("msg", msg).put("version", version)
        )
    }

    private fun openConn(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("User-Agent", "carAssistant")
        return c
    }

    private fun httpGet(url: String): ByteArray {
        val c = openConn(url)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        val c = openConn(url)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32768)
                    var read = 0L
                    var lastPct = -10
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read * 100 / total).toInt()
                            if (p - lastPct >= 10) {
                                lastPct = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw RuntimeException("파일 저장 실패")
        } finally {
            c.disconnect()
        }
    }

    private fun checkUpdates(manual: Boolean) {
        lastCheckAt = System.currentTimeMillis()
        ioExec.execute {
            try {
                val j = JSONObject(String(httpGet(versionUrl), Charsets.UTF_8))
                val nativeV = j.optInt("native", 0)
                val uiV = j.optInt("ui", 0)
                val minNative = j.optInt("minNative", 1)
                val inst = installedVersionCode()
                val bad = getSharedPreferences("upd", MODE_PRIVATE).getInt("badUi", -1)

                // 1) 화면 업데이트: 조용히 내려받아 바로 적용한다.
                var uiUpdated = false
                if (uiV > curUi && uiV != bad && minNative <= inst) {
                    val html = String(httpGet(j.getString("uiUrl")), Charsets.UTF_8)
                    if (html.length > 5000 && html.contains("</html>") &&
                        uiBuildOf(html) == uiV && minNativeOf(html) <= inst
                    ) {
                        val f = uiFile()
                        f.parentFile?.mkdirs()
                        f.writeText(html)
                        uiUpdated = true
                    }
                }

                // 2) 앱 업데이트: 내려받기까지 자동, 설치는 확인 버튼 한 번.
                if (nativeV > inst) {
                    updState = "새 버전 내려받는 중"
                    upd("downloading", "", nativeV)
                    val dest = File(File(cacheDir, "update"), "carAssistant.apk")
                    download(j.getString("apkUrl"), dest) { p -> upd("downloading", "$p%", nativeV) }
                    if (dest.length() < 500_000) throw RuntimeException("받은 파일이 올바르지 않습니다")
                    apkFile = dest
                    updState = "설치 대기 (v$nativeV)"
                    upd("ready", "", nativeV)
                } else {
                    updState = if (uiUpdated) "화면 업데이트됨" else "최신 버전"
                    if (manual) upd("none", "최신 버전입니다", inst)
                }
                if (uiUpdated) main.post { loadUi() }
            } catch (e: Exception) {
                updState = "확인 실패"
                if (manual) upd("error", "업데이트 확인 실패: ${e.message}", 0)
            }
        }
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val conf = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (conf != null) {
                        conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(conf)
                        } catch (e: Exception) {
                            upd("error", "설치 화면을 열지 못했습니다", 0)
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> upd("done", "설치 완료", 0)
                else -> upd(
                    "error",
                    "설치 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
                    0
                )
            }
        }
    }

    private fun installUpdate() {
        val f = apkFile
        if (f == null || !f.exists()) {
            upd("error", "설치할 파일이 없습니다. 업데이트 확인을 먼저 해 주세요", 0)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            upd("needPermission", "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요", 0)
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
            }
            return
        }
        ioExec.execute {
            try {
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                val s = pi.openSession(id)
                f.inputStream().use { ins ->
                    s.openWrite("carAssistant", 0, f.length()).use { out ->
                        ins.copyTo(out)
                        s.fsync(out)
                    }
                }
                val intent = Intent(actionInstall).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(
                    this, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                upd("installing", "설치 확인 화면이 나오면 '설치'를 눌러 주세요", 0)
                s.commit(pending.intentSender)
                s.close()
            } catch (e: Exception) {
                upd("error", "설치 시작 실패: ${e.message}", 0)
            }
        }
    }

    // ---------- 위치 ----------

    private fun locJson(l: Location): JSONObject {
        val o = JSONObject()
        o.put("lat", l.latitude)
        o.put("lon", l.longitude)
        o.put("acc", l.accuracy.toDouble())
        o.put("speed", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
        o.put("bearing", if (l.hasBearing()) l.bearing.toDouble() else JSONObject.NULL)
        o.put("alt", if (l.hasAltitude()) l.altitude else JSONObject.NULL)
        o.put("provider", l.provider ?: "")
        o.put("time", l.time)
        return o
    }

    private fun pushLocation() {
        val l = lastLoc ?: return
        js("onNativeLocation", locJson(l))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocation()) return
        stopLocation()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locMgr = lm
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLoc = location
                lastLocAt = System.currentTimeMillis()
                pushLocation()
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }
        }
        locListener = listener
        val providers = try {
            lm.allProviders
        } catch (e: Exception) {
            emptyList<String>()
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!providers.contains(p)) continue
            try {
                lm.requestLocationUpdates(p, 2000L, 0f, listener, Looper.getMainLooper())
                val last = lm.getLastKnownLocation(p)
                val cur = lastLoc
                if (last != null && (cur == null || last.time > cur.time)) {
                    lastLoc = last
                    lastLocAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // 권한 부족 또는 공급자 오류: 진단 화면에서 확인
            }
        }
        if (lastLoc != null) pushLocation()
    }

    private fun stopLocation() {
        val l = locListener
        if (l != null) {
            try {
                locMgr?.removeUpdates(l)
            } catch (e: Exception) {
            }
        }
        locListener = null
    }

    // ---------- 음성 출력 ----------

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    ttsKo = tts?.setLanguage(Locale.KOREAN) ?: -99
                }
            }
        } catch (e: Exception) {
            ttsReady = false
        }
    }

    private fun speak(text: String) {
        val t = tts
        if (ttsReady && ttsKo >= 0 && t != null) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cmd")
        }
    }

    // ---------- 음성 인식 ----------

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류 (인식 서버에 연결하지 못함)"
        SpeechRecognizer.ERROR_AUDIO -> "마이크 입력 오류"
        SpeechRecognizer.ERROR_SERVER -> "인식 서버 오류"
        SpeechRecognizer.ERROR_CLIENT -> "인식기 오류"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀이 들리지 않았습니다"
        SpeechRecognizer.ERROR_NO_MATCH -> "알아듣지 못했습니다"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 사용 중입니다"
        11 -> "음성 인식 서비스 연결이 끊겼습니다. 다시 눌러 주세요"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다"
        else -> "알 수 없는 오류($code)"
    }

    private fun speechEvent(type: String, text: String): JSONObject =
        JSONObject().put("type", type).put("text", text)

    private var sttRetry = 0
    private var sttCancelled = false
    private val focusRelease = Runnable { abandonFocus() }
    private var ducked = false
    private var focusReq: AudioFocusRequest? = null
    private var focusHeld = false
    private var resumePending = false

    // 소리 처리 설정 (차량 라디오가 어떤 방식에 반응하는지 시험하려고 선택 가능하게 둔다)
    //  duck: 0 그대로 / 1 볼륨 0으로 낮췄다가 복구 / 2 오디오 포커스 요청
    //  resume: 인식이 끝난 뒤 '재생' 미디어 키를 보내 라디오를 다시 켜 보기
    private fun audioDuck(): Int = getSharedPreferences("audio", MODE_PRIVATE).getInt("duck", 1)
    private fun audioResume(): Boolean = getSharedPreferences("audio", MODE_PRIVATE).getBoolean("resume", false)

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            when (audioDuck()) {
                1 -> if (!ducked) {
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (cur > 0) {
                        getSharedPreferences("upd", MODE_PRIVATE).edit()
                            .putInt("savedVol", cur).putBoolean("ducking", true).apply()
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        ducked = true
                    }
                }
                2 -> if (!focusHeld) {
                    if (focusReq == null) {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener { }
                            .build()
                    }
                    focusHeld = am.requestAudioFocus(focusReq!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            }
        } catch (e: Exception) {
        }
        if (audioResume()) resumePending = true
        main.removeCallbacks(focusRelease)
        main.postDelayed(focusRelease, 25000)
    }

    private fun abandonFocus() {
        main.removeCallbacks(focusRelease)
        val pr = getSharedPreferences("upd", MODE_PRIVATE)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (ducked || pr.getBoolean("ducking", false)) {
            ducked = false
            pr.edit().putBoolean("ducking", false).apply()
            try {
                val saved = pr.getInt("savedVol", -1)
                if (saved > 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            } catch (e: Exception) {
            }
        }
        if (focusHeld) {
            focusHeld = false
            try {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
            } catch (e: Exception) {
            }
        }
        if (resumePending) {
            resumePending = false
            // 복구가 끝난 뒤 잠깐 있다가 '재생' 키를 보낸다.
            main.postDelayed({
                try {
                    val t = android.os.SystemClock.uptimeMillis()
                    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
                } catch (e: Exception) {
                }
            }, 500)
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            main.postDelayed({ tg.release() }, 400)
        } catch (e: Exception) {
        }
    }

    private fun newRecognizer(): SpeechRecognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sttCancelled) return
                js("onNativeSpeech", speechEvent("ready", ""))
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                js("onNativeSpeech", speechEvent("end", ""))
            }

            override fun onError(error: Int) {
                if (sttCancelled) return
                // 11: 인식 서비스 연결 끊김, 5: 인식기 오류, 8: 사용 중 -> 인식기를 새로 만들어 한 번 자동 재시도
                if ((error == 11 || error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && sttRetry < 1
                ) {
                    sttRetry += 1
                    resetRecognizer()
                    main.postDelayed({ startListening() }, 600)
                    return
                }
                if (error == 11) resetRecognizer()
                abandonFocus()
                js("onNativeSpeech", speechEvent("error", speechError(error)))
            }

            override fun onResults(results: Bundle?) {
                if (sttCancelled) return
                sttRetry = 0
                abandonFocus()
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = if (list != null && list.isNotEmpty()) list[0] else ""
                js("onNativeSpeech", speechEvent("result", text))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sttCancelled) return
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = if (list != null && list.isNotEmpty()) list[0] else ""
                if (text.isNotEmpty()) js("onNativeSpeech", speechEvent("partial", text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    private fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun startListening(fromUser: Boolean = false) {
        if (fromUser) {
            sttRetry = 0
            sttCancelled = false
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            js("onNativeSpeech", speechEvent("error", "마이크 권한이 없습니다"))
            requestPermissions(perms, reqCode)
            return
        }
        if (sttCancelled && !fromUser) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onNativeSpeech", speechEvent("error", "이 기기에는 음성 인식 서비스가 없습니다"))
            return
        }
        // 인식기는 한 번 만들어 재사용한다. 매번 destroy 후 바로 새로 만들면 서비스 연결이 끊긴다.
        val r = recognizer ?: newRecognizer().also { recognizer = it }
        try {
            r.cancel()
        } catch (e: Exception) {
        }
        if (fromUser) {
            beep()
            main.postDelayed({
                requestFocus()
                beginRecognition(r)
            }, 350)
        } else {
            requestFocus()
            beginRecognition(r)
        }
    }

    private fun beginRecognition(r: SpeechRecognizer) {
        if (sttCancelled) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            resetRecognizer()
            js("onNativeSpeech", speechEvent("error", "인식기를 시작하지 못했습니다"))
        }
    }

    // ---------- 핸들 리모컨·차량 버튼 점검 ----------

    private fun keyEmit(src: String, e: KeyEvent) {
        if (e.repeatCount > 0) return
        val dev = try {
            InputDevice.getDevice(e.deviceId)?.name ?: "id${e.deviceId}"
        } catch (ex: Exception) {
            "id${e.deviceId}"
        }
        js(
            "onNativeKey",
            JSONObject()
                .put("src", src)
                .put("name", KeyEvent.keyCodeToString(e.keyCode))
                .put("code", e.keyCode)
                .put("scan", e.scanCode)
                .put("action", if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP")
                .put("dev", dev)
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyProbe) keyEmit("key", event)
        return super.dispatchKeyEvent(event)
    }

    private fun startMediaSession() {
        if (mediaSession != null) return
        try {
            val ms = MediaSession(this, "carAssistant")
            ms.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ke = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ke != null) keyEmit("media", ke)
                    return true
                }
            })
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY_PAUSE)
                    .build()
            )
            ms.isActive = true
            mediaSession = ms
        } catch (e: Exception) {
            mediaSession = null
        }
    }

    private fun stopMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
        }
        mediaSession = null
    }

    private fun setKeyProbe(on: Boolean) {
        keyProbe = on
        if (on) startMediaSession() else stopMediaSession()
    }

    // ---------- AI (오픈라우터 무료 모델) ----------
    // 키는 이 기기의 앱 전용 저장소에만 두고, 화면(JS)에는 넘기지 않는다. 호출도 네이티브에서 한다.

    private fun aiKey(): String = getSharedPreferences("ai", MODE_PRIVATE).getString("key", "") ?: ""

    private fun aiEmit(ok: Boolean, text: String, model: String, err: String) {
        js("onNativeAi", JSONObject().put("ok", ok).put("text", text).put("model", model).put("err", err))
    }

    private fun askAi(modelsJson: String, messagesJson: String) {
        val key = aiKey()
        if (key.isEmpty()) {
            aiEmit(false, "", "", "AI 키가 없습니다")
            return
        }
        val models = JSONArray(modelsJson)
        val msgs = JSONArray(messagesJson)
        var lastErr = "응답을 받지 못했습니다"
        for (i in 0 until models.length()) {
            val m = models.getString(i)
            try {
                val body = JSONObject()
                    .put("model", m)
                    .put("messages", msgs)
                    .put("max_tokens", 500)
                    .put("temperature", 0.5)
                val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 40000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("HTTP-Referer", "https://github.com/kjt7942/carAssistant")
                c.setRequestProperty("X-Title", "carAssistant")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                c.disconnect()
                if (code == 401 || code == 403) {
                    aiEmit(false, "", m, "AI 키가 올바르지 않거나 막혔습니다 ($code)")
                    return
                }
                if (code !in 200..299) {
                    lastErr = if (code == 429) "무료 사용 한도에 걸렸습니다 (429)" else "서버 오류 ($code)"
                    continue
                }
                val content = JSONObject(txt).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
                val clean = content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                if (clean.isEmpty()) {
                    lastErr = "빈 응답 ($m)"
                    continue
                }
                aiEmit(true, clean, m, "")
                return
            } catch (e: Exception) {
                lastErr = "연결 실패: ${e.message}"
            }
        }
        aiEmit(false, "", "", lastErr)
    }

    // ---------- 화면에서 부르는 기능 ----------

    inner class Bridge {
        @JavascriptInterface
        fun listen() {
            main.post { startListening(true) }
        }

        @JavascriptInterface
        fun stopListening() {
            main.post {
                // 사용자가 닫기를 눌렀다: 이후 오는 오류·결과·자동 재시도를 모두 무시한다.
                sttCancelled = true
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                }
                abandonFocus()
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            main.post { this@MainActivity.speak(text) }
        }

        @JavascriptInterface
        fun canSpeak(): Boolean = ttsReady && ttsKo >= 0

        @JavascriptInterface
        fun requestPerms() {
            main.post { requestPermissions(perms, reqCode) }
        }

        @JavascriptInterface
        fun startLocation() {
            main.post { this@MainActivity.startLocation() }
        }

        /** 화면이 정상적으로 시작되었다고 알린다. (내려받은 화면의 안전장치) */
        @JavascriptInterface
        fun uiOk() {
            uiOkFlag = true
        }

        @JavascriptInterface
        fun checkUpdate() {
            this@MainActivity.checkUpdates(true)
        }

        @JavascriptInterface
        fun installUpdate() {
            main.post { this@MainActivity.installUpdate() }
        }

        @JavascriptInterface
        fun listApps(): String {
            val arr = JSONArray()
            try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                val list = packageManager.queryIntentActivities(i, 0)
                    .map { Pair(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
                    .sortedBy { it.first.lowercase() }
                for ((label, pkg) in list) {
                    if (!seen.add(pkg)) continue
                    arr.put(JSONObject().put("n", label).put("p", pkg))
                }
            } catch (e: Exception) {
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun launchApp(pkg: String): Boolean {
            return try {
                val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun inputDevices(): String {
            val arr = JSONArray()
            try {
                for (id in InputDevice.getDeviceIds()) {
                    val d = InputDevice.getDevice(id) ?: continue
                    val src = d.sources
                    val kind = when {
                        src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> "키"
                        src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD -> "방향키"
                        src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN -> "터치"
                        else -> "기타"
                    }
                    arr.put(JSONObject().put("id", id).put("n", d.name).put("k", kind))
                }
            } catch (e: Exception) {
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun setKeyProbe(on: Boolean) {
            main.post { this@MainActivity.setKeyProbe(on) }
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("full", on).apply()
            main.post { applyFullscreen() }
        }

        @JavascriptInterface
        fun getFullscreen(): Boolean = fullscreenOn()

        @JavascriptInterface
        fun setAudio(duck: Int, resume: Boolean) {
            getSharedPreferences("audio", MODE_PRIVATE).edit()
                .putInt("duck", duck).putBoolean("resume", resume).apply()
        }

        @JavascriptInterface
        fun getAudio(): String {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val o = JSONObject().put("duck", audioDuck()).put("resume", audioResume())
            try {
                o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                o.put("active", am.isMusicActive)
            } catch (e: Exception) {
            }
            return o.toString()
        }

        @JavascriptInterface
        fun setApiKey(k: String): Boolean {
            val t = k.trim()
            if (!t.startsWith("sk-or-") || t.length < 20) return false
            getSharedPreferences("ai", MODE_PRIVATE).edit().putString("key", t).apply()
            return true
        }

        @JavascriptInterface
        fun clearApiKey() {
            getSharedPreferences("ai", MODE_PRIVATE).edit().remove("key").apply()
        }

        @JavascriptInterface
        fun hasApiKey(): Boolean = aiKey().isNotEmpty()

        @JavascriptInterface
        fun keyTail(): String = aiKey().takeLast(4)

        @JavascriptInterface
        fun ask(modelsJson: String, messagesJson: String) {
            aiExec.execute {
                try {
                    askAi(modelsJson, messagesJson)
                } catch (e: Exception) {
                    aiEmit(false, "", "", "오류: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun diag(): String {
            val o = JSONObject()
            o.put("model", Build.MODEL)
            o.put("release", Build.VERSION.RELEASE)
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("versionCode", installedVersionCode())
            o.put("uiBuild", curUi)
            o.put("uiSource", uiSource)
            o.put("updState", updState)
            o.put("canInstall", packageManager.canRequestPackageInstalls())
            o.put("aiKey", aiKey().isNotEmpty())
            try {
                val ba = BluetoothAdapter.getDefaultAdapter()
                o.put("btHas", ba != null)
                o.put("btOn", ba?.isEnabled ?: false)
                val names = JSONArray()
                ba?.bondedDevices?.forEach { names.put(it.name ?: it.address) }
                o.put("btBonded", names)
            } catch (e: Exception) {
                o.put("btHas", false)
                o.put("btErr", e.javaClass.simpleName)
            }
            o.put("fine", granted(Manifest.permission.ACCESS_FINE_LOCATION))
            o.put("coarse", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            o.put("mic", granted(Manifest.permission.RECORD_AUDIO))
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val all = lm.allProviders
                o.put("gpsProvider", all.contains(LocationManager.GPS_PROVIDER))
                o.put(
                    "gpsOn",
                    all.contains(LocationManager.GPS_PROVIDER) &&
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                )
                o.put("netProvider", all.contains(LocationManager.NETWORK_PROVIDER))
                o.put(
                    "netOn",
                    all.contains(LocationManager.NETWORK_PROVIDER) &&
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                )
            } catch (e: Exception) {
                o.put("gpsProvider", false)
                o.put("gpsOn", false)
                o.put("netProvider", false)
                o.put("netOn", false)
            }
            o.put(
                "speech",
                try {
                    SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
                } catch (e: Exception) {
                    false
                }
            )
            o.put("ttsReady", ttsReady)
            o.put("ttsKo", ttsKo)
            o.put(
                "ttsEngines",
                try {
                    tts?.engines?.size ?: 0
                } catch (e: Exception) {
                    0
                }
            )
            val l = lastLoc
            if (l != null) {
                val j = locJson(l)
                j.put("ageSec", (System.currentTimeMillis() - lastLocAt) / 1000)
                o.put("loc", j)
            }
            return o.toString()
        }
    }
}
