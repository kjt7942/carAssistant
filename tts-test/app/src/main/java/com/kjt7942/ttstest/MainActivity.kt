package com.kjt7942.ttstest

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Supertonic 3 (sherpa-onnx) 한국어 음성 시험용 앱: 목소리 번호별로 들어 보고, 만드는 데 걸리는 시간을 잰다.
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var info: TextView
    private var tts: OfflineTts? = null
    private var sid = 0
    private var speed = 1.0f
    private var steps = 5
    private var track: AudioTrack? = null
    private var busy = false

    private val modelUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    private fun modelDir() = File(filesDir, "model")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        status = TextView(this).apply { textSize = 16f; text = "준비 중…" }
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText("안녕하세요, 종태님. 오늘 문경의 날씨는 맑고 기온은 십이 도입니다. 오후 세 시에 회의가 하나 있습니다.")
        }
        info = TextView(this).apply { textSize = 15f; text = "" }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row1.addView(btn("◀ 이전 목소리") { stepSid(-1) })
        row1.addView(btn("다음 목소리 ▶") { stepSid(1) })
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row2.addView(btn("느리게") { speed = 0.9f; speak() })
        row2.addView(btn("보통") { speed = 1.0f; speak() })
        row2.addView(btn("빠르게") { speed = 1.15f; speak() })
        val row3 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row3.addView(btn("빠른 품질(5단계)") { steps = 5; speak() })
        row3.addView(btn("높은 품질(10단계)") { steps = 10; speak() })
        root.addView(status); root.addView(input)
        root.addView(btn("모델 받기 (처음 한 번, 약 100MB 이상)") { download() })
        root.addView(btn("말하기") { speak() })
        root.addView(row1); root.addView(row2); root.addView(row3); root.addView(info)
        setContentView(ScrollView(this).apply { addView(root) })
        if (File(modelDir(), "tts.json").exists()) load() else status.text = "모델이 없습니다. 아래 \"모델 받기\"를 누르세요. (Wi-Fi 권장)"
    }

    private fun ui(t: String) = main.post { status.text = t }

    private fun download() {
        if (busy) return
        busy = true
        exec.execute {
            try {
                modelDir().deleteRecursively(); modelDir().mkdirs()
                val c = URL(modelUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000
                val total = c.contentLengthLong
                var got = 0L
                val counting = object : java.io.FilterInputStream(c.inputStream) {
                    override fun read(buf: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(buf, off, len)
                        if (n > 0) {
                            got += n
                            if (got % 65536L < n) ui("모델 받는 중… ${got / 1048576}MB" + if (total > 0) " / ${total / 1048576}MB" else "")
                        }
                        return n
                    }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                    while (true) {
                        val e = tar.nextTarEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.substringAfter('/') // 맨 위 폴더 이름 제거
                        if (name.isEmpty()) continue
                        val f = File(modelDir(), name)
                        f.parentFile?.mkdirs()
                        f.outputStream().use { o -> tar.copyTo(o) }
                    }
                }
                c.disconnect()
                ui("모델을 받았습니다. 불러오는 중…")
                busy = false
                load()
            } catch (e: Throwable) {
                busy = false
                ui("받기 실패: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun load() {
        exec.execute {
            try {
                val t0 = System.currentTimeMillis()
                val d = modelDir().path
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        supertonic = OfflineTtsSupertonicModelConfig(
                            durationPredictor = "$d/duration_predictor.int8.onnx",
                            textEncoder = "$d/text_encoder.int8.onnx",
                            vectorEstimator = "$d/vector_estimator.int8.onnx",
                            vocoder = "$d/vocoder.int8.onnx",
                            ttsJson = "$d/tts.json",
                            unicodeIndexer = "$d/unicode_indexer.bin",
                            voiceStyle = "$d/voice.bin",
                        ),
                        numThreads = 4,
                    ),
                )
                tts?.release()
                tts = OfflineTts(config = cfg)
                val t = tts!!
                ui("준비 완료 (불러오는 데 ${(System.currentTimeMillis() - t0) / 1000.0}초). 목소리 ${t.numSpeakers()}개, ${t.sampleRate()}Hz")
            } catch (e: Throwable) {
                ui("불러오기 실패: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun stepSid(d: Int) {
        val n = tts?.numSpeakers() ?: 1
        sid = ((sid + d) % n + n) % n
        speak()
    }

    private fun speak() {
        val t = tts ?: run { status.text = "아직 준비되지 않았습니다."; return }
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        busy = true
        val mySid = sid; val mySpeed = speed; val mySteps = steps
        ui("만드는 중… (목소리 $mySid)")
        exec.execute {
            try {
                val t0 = System.currentTimeMillis()
                val a = t.generateWithConfig(
                    text = text,
                    config = GenerationConfig(sid = mySid, speed = mySpeed, numSteps = mySteps, extra = mapOf("lang" to "ko")),
                )
                val ms = System.currentTimeMillis() - t0
                val sec = a.samples.size.toDouble() / a.sampleRate
                main.post {
                    info.text = "목소리 $mySid · 빠르기 $mySpeed · ${mySteps}단계\n만드는 데 ${ms / 1000.0}초 / 소리 길이 ${"%.1f".format(sec)}초\n(만드는 시간이 소리 길이보다 짧아야 실시간으로 쓸 수 있습니다)"
                    status.text = "재생 중 (목소리 $mySid)"
                }
                play(a.samples, a.sampleRate)
                ui("끝 (목소리 $mySid)")
            } catch (e: Throwable) {
                ui("만들기 실패: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun play(s: FloatArray, rate: Int) {
        track?.release()
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(s.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = at
        at.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
        at.play()
        Thread.sleep((s.size * 1000L / rate) + 300)
    }
}
