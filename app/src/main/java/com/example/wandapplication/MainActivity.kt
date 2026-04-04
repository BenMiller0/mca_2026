package com.example.wandapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
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

    // ── Executors ─────────────────────────────────────────────────────────────
    private val mqttExecutor: ExecutorService    = Executors.newSingleThreadExecutor()
    /** Dedicated background thread for image analysis – never the main thread. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Detection state ───────────────────────────────────────────────────────
    private var stickDetector: StickDetector? = null
    private var isDetectionEnabled = false

    // ── Permission launcher ───────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else statusText.text = "Camera permission denied"
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

        publishButton      = findViewById(R.id.publishButton)
        cameraButton       = findViewById(R.id.cameraButton)
        previewView        = findViewById(R.id.previewView)
        cameraFrame        = findViewById(R.id.cameraFrame)
        statusText         = findViewById(R.id.statusText)
        detectionIndicator = findViewById(R.id.detectionIndicator)
        detectionStatusText = findViewById(R.id.detectionStatusText)
        wandOverlay        = findViewById(R.id.wandOverlay)

        publishButton.setOnClickListener { publishSpell(SPELL_PAYLOAD) }
        cameraButton.setOnClickListener  { openCamera() }

        stickDetector = StickDetector(this)
    }

    override fun onDestroy() {
        mqttExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
        stickDetector?.close()
        super.onDestroy()
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
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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

                // Auto-publish when a spell is recognised
                if (result.spell.isNotEmpty()) {
                    publishSpell(result.spell)
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
     * The old implementation only read plane[0] raw bytes and tried to decode them as JPEG,
     * which always produced null.
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
     * Called automatically when a spell gesture is detected, and also
     * manually via the Publish button (sends the default payload "1").
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
