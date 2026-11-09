package com.example.heygoogletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
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

    @Volatile
    private var running = false

    private var triggered = false
    private var modelLoading = false
    private var restartPending = false
    private var retryAttempt = 0

    private var assistantSessionActive = false
    private var assistantMicSeen = false
    private var assistantSessionStartedAt = 0L

    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var micReleasePending = false

    private val assistantMicMonitor = object : Runnable {
        override fun run() {
            if (!running || !assistantSessionActive) return

            val externalMicInUse = isMicrophoneUsedByAnotherApp()

            if (externalMicInUse) {
                assistantMicSeen = true
                handler.postDelayed(this, ASSISTANT_MIC_POLL_MS)
                return
            }

            if (assistantMicSeen) {
                finishAssistantSession("external microphone released")
                return
            }

            // بعض أجهزة التلفزيون لا تعرض جلسات تسجيل الصوت الخارجية.
            // لذلك يوجد حد انتظار احتياطي لتجنب توقف الخدمة نهائياً.
            if (SystemClock.elapsedRealtime() - assistantSessionStartedAt >= ASSISTANT_MIC_DETECT_TIMEOUT_MS) {
                finishAssistantSession("no external microphone session detected")
                return
            }

            handler.postDelayed(this, ASSISTANT_MIC_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        showNotification("بجهّز خدمة الاستماع...")

        if (!running) {
            running = true
            loadModelIfNeeded()
        } else if (model != null && speech == null && !triggered) {
            scheduleListeningRetry("service start requested", 500L)
        }

        return START_STICKY
    }

    private fun showNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Hey Google",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hey Google TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, n)
    }

    private fun loadModelIfNeeded() {
        if (!running || modelLoading || model != null) {
            if (model != null && !triggered && speech == null) {
                scheduleListeningRetry("model already loaded", 500L)
            }
            return
        }

        modelLoading = true

        Thread {
            try {
                val dir = File(filesDir, MODEL_NAME)

                if (!File(dir, "am/final.mdl").exists()) {
                    download()
                }

                val loaded = Model(dir.absolutePath)

                handler.post {
                    modelLoading = false

                    if (running) {
                        model = loaded
                        showNotification("النموذج جاهز، بجهّز الميكروفون...")
                        startListening()
                    } else {
                        try {
                            loaded.close()
                        } catch (_: Exception) {
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "model load failed: ${e.message}", e)

                handler.post {
                    modelLoading = false
                    showNotification("تعذر تحميل نموذج الصوت؛ هحاول تاني")

                    handler.postDelayed({
                        loadModelIfNeeded()
                    }, MODEL_RETRY_MS)
                }
            }
        }.start()
    }

    private fun download() {
        Log.i(TAG, "downloading model")

        val tmp = File(cacheDir, "model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection

        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000

        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use {
                    input.copyTo(it)
                }
            }
        } finally {
            conn.disconnect()
        }

        val modelDir = File(filesDir, MODEL_NAME)
        modelDir.deleteRecursively()

        ZipInputStream(tmp.inputStream().buffered()).use { zis ->

            var entry = zis.nextEntry

            while (entry != null) {

                val out = File(filesDir, entry.name).canonicalFile
                val filesBase = filesDir.canonicalPath + File.separator

                if (!out.path.startsWith(filesBase)) {
                    throw SecurityException("bad zip entry: ${entry.name}")
                }

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()

                    out.outputStream().use {
                        zis.copyTo(it)
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        tmp.delete()

        if (!File(modelDir, "am/final.mdl").exists()) {
            throw IllegalStateException(
                "model archive extracted to an unexpected folder"
            )
        }

        Log.i(TAG, "model ready")
    }

    private fun startListening() {

        if (!running || triggered || model == null || speech != null) {
            return
        }

        if (micReleasePending) {
            scheduleListeningRetry(
                "waiting for previous AudioRecord to release",
                MIC_RELEASE_GUARD_MS
            )
            return
        }

        restartPending = false

        try {
            val recognizer = Recognizer(
                model,
                16000.0f,
                "[\"hey google\", \"ok google\", \"okay google\", \"[unk]\"]"
            )

            val newSpeech = SpeechService(recognizer, 16000.0f)

            speech = newSpeech
            newSpeech.startListening(this)

            retryAttempt = 0

            showNotification("بسمع كلمة التنبيه...")

            Log.i(TAG, "Vosk listening started")

        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}", e)

            stopSpeech()

            scheduleListeningRetry(
                "startListening exception",
                nextRetryDelay()
            )
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        check(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        check(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        // لا يوجد إجراء مطلوب هنا.
    }

    override fun onTimeout() {
        Log.w(TAG, "Vosk recognition timeout")
        requestListeningRecovery("recognition timeout")
    }

    override fun onError(exception: Exception?) {
        Log.e(
            TAG,
            "Vosk microphone/recognition error: ${exception?.message}",
            exception
        )

        requestListeningRecovery("Vosk onError")
    }

    private fun check(json: String?) {

        if (json.isNullOrBlank() || triggered || !running) {
            return
        }

        try {
            val obj = JSONObject(json)

            val text =
                obj.optString("partial", "") +
                " " +
                obj.optString("text", "")

            if (text.contains("google", ignoreCase = true)) {
                onWake()
            }

        } catch (e: Exception) {
            Log.w(TAG, "could not parse recognition result", e)
        }
    }

    private fun onWake() {

        if (triggered || !running) {
            return
        }

        triggered = true

        assistantSessionActive = true
        assistantMicSeen = false
        assistantSessionStartedAt = SystemClock.elapsedRealtime()

        restartPending = false

        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        handler.removeCallbacks(assistantMicMonitor)

        Log.i(
            TAG,
            "wake phrase detected; pausing Vosk while Assistant uses the microphone"
        )

        // إيقاف Vosk أولاً لتحرير الميكروفون.
        stopSpeech()

        showNotification("تم إيقاف الاستماع مؤقتاً وتشغيل المساعد")

        // الانتظار 500 مللي ثانية قبل فتح Google Assistant.
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

        }, RELEASE_MIC_BEFORE_ASSISTANT_MS)
    }

    private fun isMicrophoneUsedByAnotherApp(): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        return try {

            audioManager.activeRecordingConfigurations.any { config ->
                config.clientUid != applicationInfo.uid
            }

        } catch (e: SecurityException) {

            Log.w(
                TAG,
                "cannot inspect active microphone sessions: ${e.message}"
            )

            false

        } catch (e: Exception) {

            Log.w(
                TAG,
                "active microphone inspection failed: ${e.message}"
            )

            false
        }
    }

    private fun finishAssistantSession(reason: String) {

        if (!assistantSessionActive) {
            return
        }

        assistantSessionActive = false
        assistantMicSeen = false

        handler.removeCallbacks(assistantMicMonitor)

        triggered = false

        Log.i(
            TAG,
            "Assistant microphone session ended: $reason; resuming Vosk"
        )

        showNotification("المساعد انتهى؛ برجع أسمع كلمة التنبيه")

        scheduleListeningRetry(
            "assistant microphone released",
            ASSISTANT_MIC_RESUME_MS
        )
    }

    private fun stopSpeech() {

        val old = speech
        speech = null

        if (old != null) {

            micReleasePending = true

            try {
                old.stop()
            } catch (e: Exception) {
                Log.w(TAG, "SpeechService stop failed: ${e.message}")
            }

            try {
                old.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "SpeechService shutdown failed: ${e.message}")
            }

            handler.postDelayed({

                micReleasePending = false

                if (
                    running &&
                    !triggered &&
                    model != null &&
                    speech == null &&
                    !restartPending
                ) {
                    scheduleListeningRetry(
                        "microphone release guard completed",
                        250L
                    )
                }

            }, MIC_RELEASE_GUARD_MS)
        }
    }

    private fun requestListeningRecovery(reason: String) {

        if (!running || triggered || restartPending) {
            return
        }

        Log.w(TAG, "scheduling microphone recovery: $reason")

        stopSpeech()

        scheduleListeningRetry(
            reason,
            nextRetryDelay()
        )
    }

    private fun nextRetryDelay(): Long {

        val delays = longArrayOf(
            5_000L,
            10_000L,
            20_000L,
            30_000L
        )

        val delay = delays[
            retryAttempt.coerceAtMost(delays.lastIndex)
        ]

        retryAttempt = (
            retryAttempt + 1
        ).coerceAtMost(delays.lastIndex)

        return delay
    }

    private fun scheduleListeningRetry(
        reason: String,
        delayMs: Long
    ) {

        if (!running || triggered || restartPending) {
            return
        }

        restartPending = true

        Log.i(
            TAG,
            "microphone retry in ${delayMs}ms ($reason)"
        )

        showNotification("الميكروفون غير جاهز؛ هحاول تاني تلقائياً")

        handler.postAtTime({

            restartPending = false

            if (!running || triggered) {
                return@postAtTime
            }

            if (model == null) {
                loadModelIfNeeded()
            } else {
                startListening()
            }

        }, RESTART_TOKEN, SystemClock.uptimeMillis() + delayMs)
    }

    private fun launchAssistant(): Boolean {

        val candidates = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_ASSIST)
                .setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_VOICE_COMMAND)
        )

        for (intent in candidates) {

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {

                startActivity(intent)

                Log.i(
                    TAG,
                    "launched via ${intent.action} pkg=${intent.`package`}"
                )

                return true

            } catch (e: ActivityNotFoundException) {

                Log.w(TAG, "no handler for ${intent.action}")

            } catch (e: SecurityException) {

                Log.w(
                    TAG,
                    "blocked ${intent.action}: ${e.message}"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "assistant launch failed for ${intent.action}",
                    e
                )
            }
        }

        Log.e(TAG, "could not launch assistant")

        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {

        running = false
        assistantSessionActive = false

        handler.removeCallbacksAndMessages(null)

        stopSpeech()

        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(TAG, "model close failed: ${e.message}")
        }

        model = null

        super.onDestroy()

        Log.w(TAG, "service destroyed")
    }

    companion object {

        private const val TAG = "HeyGoogleTV"
        private const val CHANNEL = "wake"

        private const val MODEL_NAME =
            "vosk-model-small-en-us-0.15"

        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        private const val MODEL_RETRY_MS = 30_000L

        private const val MIC_RELEASE_GUARD_MS = 2_000L

        private const val RELEASE_MIC_BEFORE_ASSISTANT_MS = 500L

        private const val ASSISTANT_MIC_POLL_MS = 500L

        private const val ASSISTANT_MIC_DETECT_TIMEOUT_MS = 15_000L

        private const val ASSISTANT_MIC_RESUME_MS = 500L

        private val RESTART_TOKEN = Any()
    }
}
