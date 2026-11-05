package com.kjt7942.carassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Locale

/**
 * 차량 비서: WebView(화면) + 네이티브 기능(GPS, 음성 인식, 음성 출력)을 연결하는 단일 화면 앱.
 * 화면은 assets/index.html, 네이티브 기능은 Android 객체(Bridge)로 호출한다.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsKo = -99

    private var recognizer: SpeechRecognizer? = null

    private var locMgr: LocationManager? = null
    private var locListener: LocationListener? = null
    private var lastLoc: Location? = null
    private var lastLocAt = 0L

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
        web.loadUrl("file:///android_asset/index.html")

        initTts()
        if (!hasAll()) requestPermissions(perms, reqCode)
    }

    override fun onResume() {
        super.onResume()
        startLocation()
    }

    override fun onPause() {
        stopLocation()
        super.onPause()
    }

    override fun onDestroy() {
        stopLocation()
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

        @JavascriptInterface
        fun diag(): String {
            val o = JSONObject()
            o.put("model", Build.MODEL)
            o.put("release", Build.VERSION.RELEASE)
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("fine", granted(Manifest.permission.ACCESS_FINE_LOCATION))
            o.put("coarse", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            o.put("mic", granted(Manifest.permission.RECORD_AUDIO))
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val all = lm.allProviders
                o.put("gpsProvider", all.contains(LocationManager.GPS_PROVIDER))
                o.put("gpsOn", all.contains(LocationManager.GPS_PROVIDER) &&
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                o.put("netProvider", all.contains(LocationManager.NETWORK_PROVIDER))
                o.put("netOn", all.contains(LocationManager.NETWORK_PROVIDER) &&
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            } catch (e: Exception) {
                o.put("gpsProvider", false)
                o.put("gpsOn", false)
                o.put("netProvider", false)
                o.put("netOn", false)
            }
            o.put("speech", try {
                SpeechRecognizer.isRecognitionAvailable(this@MainActivity)
            } catch (e: Exception) {
                false
            })
            o.put("ttsReady", ttsReady)
            o.put("ttsKo", ttsKo)
            o.put("ttsEngines", try {
                tts?.engines?.size ?: 0
            } catch (e: Exception) {
                0
            })
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
