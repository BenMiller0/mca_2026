package com.example.wandapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var publishButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var cameraFrame: View
    private lateinit var detectionIndicator: View
    private lateinit var detectionStatusText: TextView
    private lateinit var wandOverlay: WandOverlayView
    private lateinit var voiceText: TextView
    private lateinit var voiceLogText: TextView

    // ── Executors ─────────────────────────────────────────────────────────────
    private val mqttExecutor: ExecutorService    = Executors.newSingleThreadExecutor()
    /** Dedicated background thread for image analysis – never the main thread. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Detection state ───────────────────────────────────────────────────────
    private var stickDetector: StickDetector? = null
    private var isDetectionEnabled = false

    // ── Voice recognition ─────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null
    private var isSpeechListening = false
    private val voiceLog = ArrayDeque<String>(8)   // rolling debug log shown on screen

    // ── Permission launchers ──────────────────────────────────────────────────
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else statusText.text = "Camera permission denied"
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initRecognizerAndListen()
        else { voiceText.text = "Voice: mic permission denied"; voiceLog("permission denied") }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        publishButton       = findViewById(R.id.publishButton)
        cameraButton        = findViewById(R.id.cameraButton)
        previewView         = findViewById(R.id.previewView)
        cameraFrame         = findViewById(R.id.cameraFrame)
        statusText          = findViewById(R.id.statusText)
        detectionIndicator  = findViewById(R.id.detectionIndicator)
        detectionStatusText = findViewById(R.id.detectionStatusText)
        wandOverlay         = findViewById(R.id.wandOverlay)
        voiceText           = findViewById(R.id.voiceText)
        voiceLogText        = findViewById(R.id.voiceLogText)

        publishButton.setOnClickListener { publishSpell(SPELL_PAYLOAD) }
        cameraButton.setOnClickListener  { openCamera() }

        stickDetector = StickDetector(this)

        // Start voice recognition immediately
        requestAudioIfNeeded()
    }

    override fun onDestroy() {
        mqttExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
        stickDetector?.close()
        stopVoiceRecognition()
        super.onDestroy()
    }

    // ── Voice recognition ─────────────────────────────────────────────────────

    private fun requestAudioIfNeeded() {
        when {
            !SpeechRecognizer.isRecognitionAvailable(this) ->
                voiceLog("NOT AVAILABLE on this device")
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> initRecognizerAndListen()
            else -> requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Creates the [SpeechRecognizer] exactly once and wires up the listener.
     * After that, all cycling is done by [scheduleRestart] → [restartListening],
     * which just calls [SpeechRecognizer.startListening] on the *same* instance.
     * Destroying + recreating every cycle was the root cause of ERROR_RECOGNIZER_BUSY.
     */
    private fun initRecognizerAndListen() {
        if (speechRecognizer != null) { restartListening(); return }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isSpeechListening = true
                    voiceLog("ready – speak now")
                    voiceText.text = "Voice: listening…"
                }

                override fun onBeginningOfSpeech() {
                    voiceLog("speech detected")
                    voiceText.text = "Voice: hearing…"
                }

                override fun onRmsChanged(rmsdB: Float) { /* no-op */ }
                override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (partial.isNotEmpty()) {
                        voiceLog("partial: \"$partial\"")
                        voiceText.text = "Voice: \"$partial\"…"
                    }
                }

                override fun onEndOfSpeech() {
                    voiceLog("end of speech")
                    voiceText.text = "Voice: processing…"
                }

                override fun onResults(results: Bundle?) {
                    isSpeechListening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    voiceLog("RESULT: \"$text\"")
                    voiceText.text = "Voice: \"$text\""
                    handleVoiceSpell(text.uppercase())
                    // Reuse the same instance – just restart listening
                    scheduleRestart(delayMs = 300)
                }

                override fun onError(error: Int) {
                    isSpeechListening = false
                    val label = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH        -> "no_match"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "timeout"
                        SpeechRecognizer.ERROR_AUDIO           -> "audio_err"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                        SpeechRecognizer.ERROR_NETWORK         -> "network"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "net_timeout"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
                        else -> "err($error)"
                    }
                    voiceLog("ERROR $label")
                    voiceText.text = "Voice: $label"

                    when (error) {
                        // Recoverable without recreating — just restart
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(delayMs = 200)

                        // Service was busy — give it more breathing room, but reuse instance
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(delayMs = 2000)

                        // Fatal audio/permission error — destroy and recreate
                        else -> {
                            speechRecognizer?.destroy()
                            speechRecognizer = null
                            isSpeechListening = false
                            scheduleRestart(delayMs = 1500)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
            })
        }

        restartListening()
    }

    private fun restartListening() {
        if (isSpeechListening) return
        val sr = speechRecognizer ?: run { initRecognizerAndListen(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Suppress system beep by not using the activity-based recognizer intent
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        Log.d(TAG, "SR: startListening")
        sr.startListening(intent)
    }

    private fun scheduleRestart(delayMs: Long) {
        voiceText.removeCallbacks(restartRunnable)
        voiceText.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable { restartListening() }

    private fun stopVoiceRecognition() {
        voiceText.removeCallbacks(restartRunnable)
        isSpeechListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun handleVoiceSpell(text: String) {
        when {
            "PUSH" in text -> {
                voiceLog(">>> PUSH cast!")
                voiceText.text = "Voice: PUSH!"
                publishSpell("1")
            }
            "LUMOS" in text -> {
                voiceLog(">>> LUMOS cast!")
                voiceText.text = "Voice: LUMOS!"
                publishSpell("2")
            }
            "SUMMON" in text -> {
                voiceLog(">>> SUMMON cast!")
                voiceText.text = "Voice: SUMMON!"
                publishSpell("3")
            }
            "OPEN" in text -> {
                voiceLog(">>> OPEN cast!")
                voiceText.text = "Voice: OPEN!"
                // Payload "4" is not handled by the Arduino — web only
                publishSpell("4")
            }
        }
    }

    /** Appends a timestamped line to the on-screen debug log (max 6 lines). */
    private fun voiceLog(msg: String) {
        val ts = System.currentTimeMillis() % 100000   // last 5 digits of epoch ms
        Log.d(TAG, "SR: $msg")
        voiceLog.addLast("${ts}ms $msg")
        while (voiceLog.size > 6) voiceLog.removeFirst()
        voiceLogText.text = voiceLog.joinToString("\n")
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun openCamera() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            statusText.text = "No camera available on this device"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    // Run on background thread – never block the UI thread with CV work.
                    ia.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (isDetectionEnabled) {
                            processFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

                cameraFrame.visibility = View.VISIBLE
                statusText.text = "Camera ready – tap preview to toggle detection"

                previewView.setOnClickListener { toggleDetection() }

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                statusText.text = "Camera init failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleDetection() {
        isDetectionEnabled = !isDetectionEnabled
        if (isDetectionEnabled) {
            stickDetector?.clearHistory()
            statusText.text = "Wand detection ON – point your wand at the camera"
            cameraButton.text = "Detection: ON (tap preview to stop)"
        } else {
            statusText.text = "Wand detection paused"
            cameraButton.text = "Detection: OFF (tap preview to start)"
            runOnUiThread {
                wandOverlay.clearWand()
                updateIndicator(false)
            }
        }
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        bitmap ?: return

        val result = stickDetector?.detectWand(bitmap) ?: return

        runOnUiThread {
            if (result.isDetected) {
                wandOverlay.updateWand(result.tipX, result.tipY, result.spell)
                updateIndicator(true)
                statusText.text = result.message

                // Publish wand gesture only when the tip is tracked with strong confidence
                if (result.spell.isNotEmpty() && result.confidence >= 0.7f) {
                    when (result.spell) {
                        "PUSH"   -> publishSpell("1")
                        "LUMOS"  -> publishSpell("2")
                        "SUMMON" -> publishSpell("3")
                    }
                }
            } else {
                wandOverlay.clearWand()
                updateIndicator(false)
                statusText.text = result.message
            }
        }
    }

    /**
     * Correctly converts a YUV_420_888 [ImageProxy] (what CameraX delivers) to a [Bitmap].
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            if (imageProxy.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unexpected image format: ${imageProxy.format}")
                return null
            }

            val yBuf = imageProxy.planes[0].buffer
            val uBuf = imageProxy.planes[1].buffer
            val vBuf = imageProxy.planes[2].buffer

            val ySize = yBuf.remaining()
            val uSize = uBuf.remaining()
            val vSize = vBuf.remaining()

            // Build NV21 byte array (Y plane + interleaved V/U)
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuf.get(nv21, 0, ySize)
            vBuf.get(nv21, ySize, vSize)
            uBuf.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, out)
            val jpegBytes = out.toByteArray()

            val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

            // Apply sensor rotation so the image is upright
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation == 0) raw else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { if (it != raw) raw.recycle() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV→Bitmap conversion failed", e)
            null
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateIndicator(detected: Boolean) {
        val color = when {
            !isDetectionEnabled -> getColor(android.R.color.darker_gray)
            detected            -> getColor(android.R.color.holo_green_dark)
            else                -> getColor(android.R.color.holo_orange_dark)
        }
        val label = when {
            !isDetectionEnabled -> "Wand Detection: OFF"
            detected            -> "Wand Detection: LOCKED"
            else                -> "Wand Detection: SCANNING"
        }
        detectionIndicator.setBackgroundColor(color)
        detectionStatusText.text  = label
        detectionStatusText.setTextColor(color)
    }

    // ── MQTT ──────────────────────────────────────────────────────────────────

    /**
     * Publishes [payload] to the Arduino via MQTT.
     * Called automatically when a spell gesture or voice spell is detected,
     * and also manually via the Publish button (sends the default payload "1").
     */
    fun publishSpell(payload: String = SPELL_PAYLOAD) {
        publishButton.isEnabled = false
        statusText.text = getString(R.string.status_sending)

        mqttExecutor.execute {
            var client: MqttClient? = null
            try {
                client = MqttClient(MQTT_BROKER_URI, MqttClient.generateClientId(), MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isAutomaticReconnect = false
                    isCleanSession       = true
                    connectionTimeout    = 10
                }
                val msg = MqttMessage(payload.toByteArray()).apply {
                    qos       = 1
                    isRetained = false
                }
                client.connect(opts)
                client.publish(MQTT_TOPIC, msg)

                runOnUiThread {
                    statusText.text        = getString(R.string.status_success)
                    publishButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT publish failed", e)
                runOnUiThread {
                    statusText.text        = formatFailure(e)
                    publishButton.isEnabled = true
                }
            } finally {
                try {
                    if (client?.isConnected == true) client.disconnect()
                    client?.close()
                } catch (ex: MqttException) {
                    Log.w(TAG, "MQTT cleanup failed", ex)
                }
            }
        }
    }

    private fun formatFailure(e: Exception): String {
        val code   = (e as? MqttException)?.reasonCode
        val detail = generateSequence(e as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
            .firstOrNull() ?: e.message ?: "unknown error"
        return if (code != null && code != 0) "Status: failed ($code, $detail)"
               else                           "Status: failed ($detail)"
    }

    companion object {
        private const val TAG             = "MainActivity"
        private const val MQTT_BROKER_URI = "tcp://172.20.10.5:1883"
        private const val MQTT_TOPIC      = "spell/cast"
        /** Default payload for the manual Publish button. */
        private const val SPELL_PAYLOAD   = "1"
    }
}
