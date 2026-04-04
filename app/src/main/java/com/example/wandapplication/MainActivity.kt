package com.example.wandapplication

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var statusText: TextView
    private val mqttExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
        statusText = findViewById(R.id.statusText)

        publishButton.setOnClickListener {
            publishSpell()
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

    companion object {
        private const val TAG = "MainActivity"
        private const val MQTT_BROKER_URI = "tcp://172.20.10.5:1883"
        private const val MQTT_TOPIC = "spell/cast"
        private const val SPELL_PAYLOAD = "1"
    }
}
