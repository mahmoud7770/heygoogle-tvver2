package com.example.heygoogletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class WakeService : Service(), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var running = false
    private var triggered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("بحمّل نموذج الصوت...")
        if (!running) {
            running = true
            Thread { loadModelAndStart() }.start()
        }
        return START_STICKY
    }

    private fun showNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Google", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hey Google TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n)
    }

    private fun loadModelAndStart() {
        try {
            val dir = File(filesDir, MODEL_NAME)
            if (!File(dir, "am/final.mdl").exists()) {
                download()
            }
            model = Model(dir.absolutePath)
            handler.post { startListening() }
        } catch (e: Exception) {
            Log.e(TAG, "model load failed: ${e.message}", e)
            File(filesDir, MODEL_NAME).deleteRecursively()
            handler.postDelayed({
                if (running) Thread { loadModelAndStart() }.start()
            }, 30_000)
        }
    }

    private fun download() {
        Log.i(TAG, "downloading model")
        val tmp = File(cacheDir, "model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }

        File(filesDir, MODEL_NAME).deleteRecursively()
        val base = filesDir.canonicalPath
        ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(filesDir, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad zip entry")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        tmp.delete()
        Log.i(TAG, "model ready")
    }

    private fun startListening() {
        if (!running || triggered) return
        try {
            val rec = Recognizer(model, 16000.0f, "[\"hey google\", \"ok google\", \"okay google\", \"[unk]\"]")
            speech = SpeechService(rec, 16000.0f)
            speech?.startListening(this)
            showNotification("بسمع كلمة التنبيه...")
            Log.i(TAG, "listening")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}", e)
            handler.postDelayed({ startListening() }, 3000)
        }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis)
    override fun onResult(hypothesis: String?) = check(hypothesis)
    override fun onFinalResult(hypothesis: String?) {}
    override fun onTimeout() {}
    override fun onError(exception: Exception?) {
        Log.e(TAG, "vosk error: ${exception?.message}", exception)
    }

    private fun check(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            val t = o.optString("partial", "") + " " + o.optString("text", "")
            if (t.contains("google")) onWake()
        } catch (_: Exception) {
        }
    }

    private fun onWake() {
    if (triggered || !running) return

    triggered = true
    assistantSessionActive = true
    assistantMicSeen = false

    restartPending = false
    handler.removeCallbacksAndMessages(RESTART_TOKEN)
    handler.removeCallbacks(assistantMicMonitor)

    Log.i(TAG, "Wake word detected - stopping Vosk")

    // إيقاف Vosk وتحرير الميكروفون
    stopSpeech()

    showNotification("جاري تشغيل المساعد الصوتي...")

    // نصف ثانية فقط قبل فتح Google Assistant
    handler.postDelayed({
        if (running && assistantSessionActive) {
            val launched = launchAssistant()

            if (launched) {
                handler.postDelayed(
                    assistantMicMonitor,
                    ASSISTANT_MIC_POLL_MS
                )
            } else {
                finishAssistantSession("assistant launch failed")
            }
        }
    }, 500L)
}

    private fun stopSpeech() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        speech = null
    }

    private fun launchAssistant() {
        val candidates = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_ASSIST).setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_VOICE_COMMAND),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                Log.i(TAG, "launched via ${i.action} pkg=${i.`package`}")
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no handler for ${i.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "blocked ${i.action}: ${e.message}")
            }
        }
        Log.e(TAG, "could not launch assistant")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopSpeech()
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeyGoogleTV"
        private const val RELEASE_MIC_BEFORE_ASSISTANT_MS = 500L
        private const val ASSISTANT_MIC_POLL_MS = 500L
        private const val CHANNEL = "wake"
        private const val RESUME_DELAY_MS = 12_000L
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
