package com.example.wandapplication

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var publishButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var detectionIndicator: View
    private lateinit var detectionStatusText: TextView
    private val mqttExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var stickDetector: StickDetector? = null
    private var isStickDetectionEnabled = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            statusText.text = "Camera permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        publishButton = findViewById(R.id.publishButton)
        cameraButton = findViewById(R.id.cameraButton)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        detectionIndicator = findViewById(R.id.detectionIndicator)
        detectionStatusText = findViewById(R.id.detectionStatusText)

        publishButton.setOnClickListener {
            publishSpell()
        }

        cameraButton.setOnClickListener {
            openCamera()
        }
        
        // Initialize stick detector
        stickDetector = StickDetector(this)
    }

    override fun onDestroy() {
        mqttExecutor.shutdownNow()
        stickDetector?.close()
        super.onDestroy()
    }

    private fun publishSpell() {
        publishButton.isEnabled = false
        statusText.text = getString(R.string.status_sending)

        mqttExecutor.execute {
            var client: MqttClient? = null
            try {
                client = MqttClient(
                    MQTT_BROKER_URI,
                    MqttClient.generateClientId(),
                    MemoryPersistence()
                )
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = false
                    isCleanSession = true
                    connectionTimeout = 10
                }
                val message = MqttMessage(SPELL_PAYLOAD.toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }

                client.connect(options)
                client.publish(MQTT_TOPIC, message)

                runOnUiThread {
                    statusText.text = getString(R.string.status_success)
                    publishButton.isEnabled = true
                }
            } catch (exception: Exception) {
                Log.e(TAG, "MQTT publish failed", exception)
                runOnUiThread {
                    statusText.text = formatFailureMessage(exception)
                    publishButton.isEnabled = true
                }
            } finally {
                try {
                    if (client?.isConnected == true) {
                        client.disconnect()
                    }
                    client?.close()
                } catch (closeException: MqttException) {
                    Log.w(TAG, "MQTT cleanup failed", closeException)
                }
            }
        }
    }

    private fun formatFailureMessage(exception: Exception): String {
        val mqttCode = (exception as? MqttException)?.reasonCode
        val detail = generateSequence(exception as Throwable?) { it.cause }
            .mapNotNull { throwable -> throwable.message?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?: exception.message
            ?: "unknown error"

        return if (mqttCode != null && mqttCode != 0) {
            "Status: failed ($mqttCode, $detail)"
        } else {
            "Status: failed ($detail)"
        }
    }

    private fun openCamera() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            statusText.text = "No camera available on this device"
            return
        }

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            
            // Setup image analysis for stick detection
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (isStickDetectionEnabled) {
                    processImageForStickDetection(imageProxy)
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )

                previewView.visibility = View.VISIBLE
                statusText.text = "Camera ready - tap preview to capture"

                previewView.setOnClickListener {
                    if (isStickDetectionEnabled) {
                        isStickDetectionEnabled = false
                        statusText.text = "Stick detection disabled"
                        cameraButton.text = "Enable Stick Detection"
                        updateDetectionIndicator(false)
                    } else {
                        isStickDetectionEnabled = true
                        statusText.text = "Stick detection enabled - looking for wands..."
                        cameraButton.text = "Disable Stick Detection"
                        updateDetectionIndicator(false)
                    }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                statusText.text = "Camera initialization failed"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        val outputDirectory = getOutputDirectory()
        val photoFile = java.io.File(outputDirectory, "wand_photo_${System.currentTimeMillis()}.jpg")
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                    statusText.text = "Photo saved: ${photoFile.name}"
                    Log.d(TAG, "Photo captured and saved: $savedUri")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    statusText.text = "Photo capture failed: ${exception.message}"
                }
            }
        )
    }

    private fun getOutputDirectory(): java.io.File {
        val mediaDirectory = externalMediaDirs.firstOrNull()?.let { mediaDir ->
            java.io.File(mediaDir, "wand_photos").apply { mkdirs() }
        }

        return mediaDirectory?.takeIf { it.exists() } ?: filesDir
    }
    
    private fun processImageForStickDetection(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        imageProxy.close()
        
        bitmap?.let { bmp ->
            val result = stickDetector?.detectStick(bmp)
            
            result?.let { detectionResult ->
                runOnUiThread {
                    if (detectionResult.isStickDetected) {
                        statusText.text = detectionResult.message
                        updateDetectionIndicator(true)
                        // You could trigger MQTT here automatically if needed
                        // publishSpell()
                    } else {
                        statusText.text = "Scanning... ${detectionResult.message}"
                        updateDetectionIndicator(false)
                    }
                }
            }
        }
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // Rotate bitmap if needed based on camera rotation
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image proxy to bitmap", e)
            null
        }
    }
    
    private fun updateDetectionIndicator(isDetected: Boolean) {
        if (isStickDetectionEnabled) {
            if (isDetected) {
                detectionIndicator.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                detectionStatusText.text = "Stick Detection: WAND DETECTED"
                detectionStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                detectionIndicator.setBackgroundColor(getColor(android.R.color.holo_orange_dark))
                detectionStatusText.text = "Stick Detection: SCANNING"
                detectionStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
            }
        } else {
            detectionIndicator.setBackgroundColor(getColor(android.R.color.darker_gray))
            detectionStatusText.text = "Stick Detection: OFF"
            detectionStatusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MQTT_BROKER_URI = "tcp://172.20.10.5:1883"
        private const val MQTT_TOPIC = "spell/cast"
        private const val SPELL_PAYLOAD = "1"
    }
}
