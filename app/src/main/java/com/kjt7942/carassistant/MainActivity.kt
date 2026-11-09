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
import android.net.ConnectivityManager
import android.net.Network
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
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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

    // 평소에는 통신을 거의 쓰지 않도록, 자동 확인은 시동(앱 시작) 때 한 번과 그 뒤 3시간 간격으로만 한다.
    // 실패하면 2분·10분·60분 뒤 다시 해 보고, 통신이 다시 붙는 순간에도 한 번 해 본다.
    private val checkEvery = 3L * 60 * 60 * 1000
    private val checkEveryColdStart = 30L * 60 * 1000
    private var coldStart = true
    private var schedulerOn = false
    private var checking = false
    private var checkFails = 0
    private var checkPending = false
    private var autoPromptedVer = 0
    private var installWaitVer = 0
    private var stillCount = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val checkRunnable = Runnable { autoCheck() }

    private fun updPrefs() = getSharedPreferences("upd", MODE_PRIVATE)

    /** 자동 확인: 마지막 성공 뒤 충분히 지났을 때만 실제로 통신한다. */
    private fun autoCheck() {
        schedulerOn = true
        main.removeCallbacks(checkRunnable)
        val gap = if (coldStart) checkEveryColdStart else checkEvery
        coldStart = false
        val since = System.currentTimeMillis() - updPrefs().getLong("lastOkAt", 0L)
        if (since in 0 until gap) {
            main.postDelayed(checkRunnable, gap - since + 1000)
            offerPendingApk()
            return
        }
        checkUpdates(false)
    }

    private fun afterCheck(ok: Boolean) {
        checking = false
        main.removeCallbacks(checkRunnable)
        if (ok) {
            checkFails = 0
            checkPending = false
            updPrefs().edit().putLong("lastOkAt", System.currentTimeMillis()).apply()
            main.postDelayed(checkRunnable, checkEvery)
        } else {
            checkFails += 1
            checkPending = true
            val wait = when (checkFails) {
                1 -> 2L
                2 -> 10L
                else -> 60L
            }
            main.postDelayed(checkRunnable, wait * 60 * 1000)
        }
    }

    private fun watchNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.postDelayed({
                        // 통신이 막 붙었다: 아까 실패한 확인이 있으면 지금 다시 한다.
                        if (checkPending && !checking && System.currentTimeMillis() - lastCheckAt > 20_000) autoCheck()
                        js("onNativeNet", JSONObject().put("on", true))
                    }, 3000)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
        }
    }

    private fun apkDest(): File = File(File(cacheDir, "update"), "carAssistant.apk")

    /** 이미 받아 둔 새 버전 파일이 있으면 다시 받지 않고 설치만 이어서 한다. 지난 파일은 지운다. */
    private fun offerPendingApk() {
        val pr = updPrefs()
        val v = pr.getInt("apkVer", 0)
        val f = apkDest()
        if (v <= installedVersionCode()) {
            if (f.exists()) f.delete()
            if (v != 0) pr.edit().remove("apkVer").apply()
            return
        }
        if (!f.exists()) return
        apkFile = f
        updState = "설치 대기 (v$v)"
        if (autoPromptedVer != v) upd("ready", "", v)
        tryAutoInstall(v)
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
        web.webViewClient = object : WebViewClient() {
            // 화면 엔진이 죽으면(메모리 부족 등) 앱이 같이 죽지 않도록 화면을 새로 만든다.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { recreate() }
                return true
            }
        }
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
        watchNetwork()
        main.postDelayed(checkRunnable, 8000)
        main.postDelayed({ announceUpdated() }, 6000)
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

    private var inFront = false
    private var backAt = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 다른 앱(MP3 등) 위에서 호출됐는지 기억해 두었다가, 끝나면 그 앱으로 돌려보낸다.
        if (!inFront) backAt = System.currentTimeMillis()
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
        inFront = true
        applyFullscreen()
        startLocation()
        // 오래 켜 둔 뒤(절전에서 깨어난 뒤 포함) 화면이 다시 보이면 확인 주기가 지났는지 본다.
        if (schedulerOn && !checking) autoCheck()
    }

    override fun onStart() {
        super.onStart()
        js("onNativeVisible", JSONObject().put("on", true))
    }

    override fun onStop() {
        js("onNativeVisible", JSONObject().put("on", false))
        super.onStop()
    }

    /** 뒤로 버튼: 열린 화면을 하나 닫고, 첫 화면이면 앱을 끄지 않고 뒤로 보낸다. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("onEndKey", JSONObject())
    }

    override fun onPause() {
        inFront = false
        setKeyProbe(false)
        abandonFocus()
        stopLocation()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocation()
        main.removeCallbacks(checkRunnable)
        main.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(installReceiver)
        } catch (e: Exception) {
        }
        try {
            netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        } catch (e: Exception) {
        }
        netCallback = null
        abandonFocus()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        try {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (e: Exception) {
        }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 새 화면 내려받기: 압축본이 있으면 그것을 받는다(약 1/3 크기). */
    private fun fetchUi(j: JSONObject): String {
        val gz = j.optString("uiGzUrl", "")
        if (gz.isNotEmpty()) {
            try {
                return GZIPInputStream(ByteArrayInputStream(httpGet(gz))).use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: Exception) {
                // 압축본을 못 받으면 원본으로 받는다
            }
        }
        return String(httpGet(j.getString("uiUrl")), Charsets.UTF_8)
    }

    private fun checkUpdates(manual: Boolean) {
        if (checking) {
            if (manual) upd("none", "확인하는 중입니다", 0)
            return
        }
        checking = true
        lastCheckAt = System.currentTimeMillis()
        ioExec.execute {
            var ok = false
            try {
                val j = JSONObject(String(httpGet(versionUrl), Charsets.UTF_8))
                val nativeV = j.optInt("native", 0)
                val uiV = j.optInt("ui", 0)
                val minNative = j.optInt("minNative", 1)
                val inst = installedVersionCode()
                val pr = updPrefs()
                val bad = pr.getInt("badUi", -1)

                // 1) 화면 업데이트: 조용히 내려받아, 음성 명령·설정 화면을 쓰고 있지 않을 때 적용한다.
                var uiUpdated = false
                if (uiV > curUi && uiV != bad && minNative <= inst) {
                    val html = fetchUi(j)
                    val want = j.optString("uiSha", "")
                    if (want.isNotEmpty() && sha256(html.toByteArray(Charsets.UTF_8)) != want) {
                        throw RuntimeException("받은 화면 파일이 손상되었습니다")
                    }
                    if (html.length > 5000 && html.contains("</html>") &&
                        uiBuildOf(html) == uiV && minNativeOf(html) <= inst
                    ) {
                        val f = uiFile()
                        f.parentFile?.mkdirs()
                        f.writeText(html)
                        uiUpdated = true
                    }
                }

                // 2) 앱 업데이트: 한 번만 내려받고(이미 받은 파일은 다시 받지 않는다), 정차 중에 설치를 자동으로 시작한다.
                if (nativeV > inst) {
                    val dest = apkDest()
                    val size = j.optLong("apkSize", 0L)
                    val have = pr.getInt("apkVer", 0) == nativeV && dest.exists() &&
                        dest.length() > 100_000 && (size <= 0L || dest.length() == size)
                    if (!have) {
                        updState = "새 버전 내려받는 중"
                        upd("downloading", "", nativeV)
                        download(j.getString("apkUrl"), dest) { p -> upd("downloading", "$p%", nativeV) }
                        val want = j.optString("apkSha", "")
                        if (dest.length() < 100_000 || (size > 0L && dest.length() != size) ||
                            (want.isNotEmpty() && sha256(dest) != want)
                        ) {
                            dest.delete()
                            throw RuntimeException("받은 설치 파일이 올바르지 않습니다")
                        }
                        pr.edit().putInt("apkVer", nativeV).apply()
                    }
                    apkFile = dest
                    updState = "설치 대기 (v$nativeV)"
                    upd("ready", "", nativeV)
                    main.post {
                        if (manual) { // 직접 확인을 눌렀으면 기다리지 않고 바로 설치를 시작한다
                            autoPromptedVer = nativeV
                            installUpdate(true)
                        } else {
                            tryAutoInstall(nativeV)
                        }
                    }
                } else {
                    updState = if (uiUpdated) "화면 업데이트됨" else "최신 버전"
                    if (manual) upd("none", "최신 버전입니다", inst)
                    main.post { offerPendingApk() }
                }
                if (uiUpdated) main.post { if (manual) loadUi() else applyUiWhenIdle(0) }
                ok = true
            } catch (e: Exception) {
                updState = "확인 실패"
                if (manual) upd("error", "업데이트 확인 실패: ${e.message}", 0)
            } finally {
                main.post { afterCheck(ok) }
            }
        }
    }

    private val busyJs = "(function(){try{return (window.uiBusy&&window.uiBusy())?1:0}catch(e){return 0}})()"

    /** 새 화면은 사용 중(음성 명령·설정 화면)이 아닐 때 바꾼다. 최대 10분 기다린다. */
    private fun applyUiWhenIdle(tries: Int) {
        web.evaluateJavascript(busyJs) { r ->
            if (r == "1" && tries < 30) main.postDelayed({ applyUiWhenIdle(tries + 1) }, 20_000) else loadUi()
        }
    }

    /** 앱이 새 버전으로 바뀐 뒤 처음 켜졌을 때 한 번 알린다. */
    private fun announceUpdated() {
        val pr = updPrefs()
        val now = installedVersionCode()
        val seen = pr.getInt("seenVer", 0)
        if (seen != now) {
            pr.edit().putInt("seenVer", now).apply()
            if (seen != 0 && now > seen) upd("updated", "앱을 v$now(으)로 업데이트했습니다", now)
        }
    }

    /**
     * 새 버전 설치를 자동으로 시작한다. 운전 중에 설치 창이 뜨지 않도록,
     * 비서 화면이 앞에 있고 · 차가 서 있고 · 통화나 음성 명령 중이 아닐 때까지 기다린다.
     */
    private fun tryAutoInstall(v: Int, tries: Int = 0) {
        if (autoPromptedVer == v) return
        if (tries == 0) {
            if (installWaitVer == v) return
            installWaitVer = v
            stillCount = 0
        }
        val f = apkFile
        if (f == null || !f.exists() || v <= installedVersionCode()) {
            installWaitVer = 0
            return
        }
        val l = lastLoc
        val moving = l != null && System.currentTimeMillis() - lastLocAt < 15_000 && l.hasSpeed() && l.speed > 1.5f
        if (!inFront || moving || inCall()) {
            stillCount = 0
            if (tries < 480) main.postDelayed({ tryAutoInstall(v, tries + 1) }, 15_000) else installWaitVer = 0
            return
        }
        stillCount += 1
        if (stillCount < 2) { // 15초 이상 계속 서 있을 때만
            main.postDelayed({ tryAutoInstall(v, tries + 1) }, 15_000)
            return
        }
        web.evaluateJavascript(busyJs) { r ->
            if (r == "1") {
                stillCount = 0
                main.postDelayed({ tryAutoInstall(v, tries + 1) }, 15_000)
            } else {
                autoPromptedVer = v
                installWaitVer = 0
                installUpdate(true)
            }
        }
    }

    private fun suPath(): String? =
        listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su")
            .firstOrNull { try { File(it).exists() } catch (e: Exception) { false } }

    /** 관리자 권한(root)이 열려 있는 기기라면 확인 창 없이 설치한다. 안 되면 false. */
    private fun rootInstall(f: File): Boolean {
        val su = suPath() ?: return false
        return try {
            val p = ProcessBuilder(su, "-c", "cat '${f.absolutePath}' | pm install -r -S ${f.length()}")
                .redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = Thread {
                try {
                    out.append(p.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                }
            }
            t.start()
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroy()
                return false
            }
            t.join(2000)
            out.contains("Success")
        } catch (e: Exception) {
            false
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
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    updState = "설치 대기"
                    upd("ready", "설치를 취소했습니다. 나중에 '지금 설치'를 누르면 됩니다", 0)
                }
                else -> upd(
                    "error",
                    "설치 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
                    0
                )
            }
        }
    }

    private fun installUpdate(auto: Boolean = false) {
        val f = apkFile
        if (f == null || !f.exists()) {
            upd("error", "설치할 파일이 없습니다. 업데이트 확인을 먼저 해 주세요", 0)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            upd("needPermission", "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요", 0)
            if (auto) return // 자동으로는 설정 화면을 띄우지 않는다. 버튼을 누르면 연다.
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
                if (suPath() != null) {
                    upd("installing", "새 버전을 설치하는 중입니다", 0)
                    if (rootInstall(f)) return@execute
                }
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
                upd("installing", "새 버전 설치 창이 뜨면 '설치'를 눌러 주세요", 0)
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
                // GPS는 2초마다, 통신을 쓰는 네트워크 위치는 GPS가 안 잡힐 때를 대비해 30초마다만 받는다.
                if (p == LocationManager.GPS_PROVIDER) {
                    lm.requestLocationUpdates(p, 2000L, 0f, listener, Looper.getMainLooper())
                } else {
                    lm.requestLocationUpdates(p, 30_000L, 200f, listener, Looper.getMainLooper())
                }
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

    /** 전화 통화 중(일반 통화·블루투스 통화·벨 울림)인지 */
    private fun inCall(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val m = am.mode
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE) true
            else {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyProbe) keyEmit("key", event)
        // 전화 종료 버튼(통화 중이 아닐 때): 뒤로 가기 역할. 맨 처음 화면이면 비서 앱을 닫고 이전 앱으로 간다.
        if (!keyProbe && event.keyCode == KeyEvent.KEYCODE_ENDCALL && inFront && !inCall()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                js("onEndKey", JSONObject())
            }
            return true
        }
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
                    tts?.stop()
                } catch (e: Exception) {
                }
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                }
                abandonFocus()
            }
        }

        @JavascriptInterface
        fun cameFromBack(): Boolean {
            val ok = backAt != 0L && System.currentTimeMillis() - backAt < 120_000
            backAt = 0L
            return ok
        }

        @JavascriptInterface
        fun backToPrev(): Boolean {
            return try {
                main.post { moveTaskToBack(true) }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            main.post { try { tts?.stop() } catch (e: Exception) { } }
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
            main.post { this@MainActivity.checkUpdates(true) }
        }

        @JavascriptInterface
        fun installUpdate() {
            main.post { this@MainActivity.installUpdate() }
        }

        /** 업데이트 뒤 앱을 자동으로 다시 켜려면 '다른 앱 위에 표시' 권한이 필요하다. */
        @JavascriptInterface
        fun canOverlay(): Boolean = try {
            Settings.canDrawOverlays(this@MainActivity)
        } catch (e: Exception) {
            false
        }

        @JavascriptInterface
        fun askOverlay() {
            main.post {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    } catch (e2: Exception) {
                    }
                }
            }
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
        fun appsReport(): String {
            val sb = StringBuilder()
            try {
                val pm = packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                val list = pm.queryIntentActivities(i, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }
                sb.append("# 설치된 앱 (실행 가능) — 이름 | 패키지 | 실행 화면\n")
                for (r in list) {
                    val pkg = r.activityInfo.packageName
                    if (!seen.add(pkg)) continue
                    sb.append(r.loadLabel(pm)).append(" | ").append(pkg).append(" | ").append(r.activityInfo.name).append("\n")
                }
                sb.append("\n# 그 밖의 관련 패키지 (실행 아이콘 없음, syu·車 관련만)\n")
                val keys = listOf("syu", "carlink", "easyconn", "navi", "radio", "mcu", "bt", "uis", "unisoc", "sprd")
                for (pi in pm.getInstalledPackages(0).sortedBy { it.packageName }) {
                    val n = pi.packageName
                    if (seen.contains(n)) continue
                    if (keys.any { n.lowercase().contains(it) }) sb.append(n).append("\n")
                }
                sb.append("\n# 기기: ").append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" / ").append(android.os.Build.DISPLAY).append("\n")
            } catch (e: Exception) {
                sb.append("목록 수집 실패: ").append(e.message)
            }
            return sb.toString()
        }

        @JavascriptInterface
        fun copyText(text: String): Boolean {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carAssistant", text))
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun shareText(text: String): Boolean {
            return try {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                val c = Intent.createChooser(i, "앱 목록 보내기")
                c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(c)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun saveText(name: String, text: String): String {
            return try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = File(dir, name)
                f.writeText(text)
                f.absolutePath
            } catch (e: Exception) {
                ""
            }
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
            o.put("overlay", try { Settings.canDrawOverlays(this@MainActivity) } catch (e: Exception) { false })
            o.put("root", suPath() != null)
            o.put("updLastOk", updPrefs().getLong("lastOkAt", 0L))
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
