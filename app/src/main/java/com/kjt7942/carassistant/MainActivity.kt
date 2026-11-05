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
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
            main.postDelayed(this, 6L * 60 * 60 * 1000)
        }
    }

    private val reqCode = 7
    private val perms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )

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

        initTts()
        if (!hasAll()) requestPermissions(perms, reqCode)
        main.postDelayed(checkRunnable, 5000)
    }

    override fun onResume() {
        super.onResume()
        startLocation()
        if (System.currentTimeMillis() - lastCheckAt > 60L * 60 * 1000 && lastCheckAt != 0L) {
            checkUpdates(false)
        }
    }

    override fun onPause() {
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
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다"
        else -> "알 수 없는 오류($code)"
    }

    private fun speechEvent(type: String, text: String): JSONObject =
        JSONObject().put("type", type).put("text", text)

    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            js("onNativeSpeech", speechEvent("error", "마이크 권한이 없습니다"))
            requestPermissions(perms, reqCode)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onNativeSpeech", speechEvent("error", "이 기기에는 음성 인식 서비스가 없습니다"))
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                js("onNativeSpeech", speechEvent("ready", ""))
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                js("onNativeSpeech", speechEvent("end", ""))
            }

            override fun onError(error: Int) {
                js("onNativeSpeech", speechEvent("error", speechError(error)))
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = if (list != null && list.isNotEmpty()) list[0] else ""
                js("onNativeSpeech", speechEvent("result", text))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = if (list != null && list.isNotEmpty()) list[0] else ""
                if (text.isNotEmpty()) js("onNativeSpeech", speechEvent("partial", text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        r.startListening(i)
    }

    // ---------- 화면에서 부르는 기능 ----------

    inner class Bridge {
        @JavascriptInterface
        fun listen() {
            main.post { startListening() }
        }

        @JavascriptInterface
        fun stopListening() {
            main.post { recognizer?.stopListening() }
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
