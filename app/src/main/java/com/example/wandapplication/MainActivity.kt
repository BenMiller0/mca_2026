package com.example.wandapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

class MainActivity : AppCompatActivity() {
    private lateinit var publishButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var statusText: TextView
    private val mqttExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var imageCapture: ImageCapture? = null
    
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
        statusText = findViewById(R.id.statusText)

        publishButton.setOnClickListener {
            publishSpell()
        }
        
        cameraButton.setOnClickListener {
            openCamera()
        }
    }

    override fun onDestroy() {
        mqttExecutor.shutdownNow()
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
                it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.previewView)?.surfaceProvider)
            }
            
            imageCapture = ImageCapture.Builder().build()
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                
                findViewById<androidx.camera.view.PreviewView>(R.id.previewView).visibility = android.view.View.VISIBLE
                statusText.text = "Camera ready - tap preview to capture"
                
                findViewById<androidx.camera.view.PreviewView>(R.id.previewView).setOnClickListener {
                    capturePhoto()
                }
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                statusText.text = "Camera initialization failed"
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        
        val photoFile = java.io.File(
            externalMediaDirs.firstOrNull(),
            "wand_photo_${System.currentTimeMillis()}.jpg"
        )
        
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: android.net.Uri.fromFile(photoFile)
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

    companion object {
        private const val TAG = "MainActivity"
        private const val MQTT_BROKER_URI = "tcp://172.20.10.5:1883"
        private const val MQTT_TOPIC = "spell/cast"
        private const val SPELL_PAYLOAD = "1"
    }
}
