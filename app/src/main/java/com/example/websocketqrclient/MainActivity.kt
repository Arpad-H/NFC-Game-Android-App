package com.example.websocketqrclient

import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import okhttp3.*
import okio.ByteString

class MainActivity : AppCompatActivity() {

    private val TAG = "WebSocketClient"
    private var webSocket: WebSocket? = null
    private lateinit var textView: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        sendButton = findViewById(R.id.sendButton)

        // Handle QR URI
        intent?.data?.let { uri ->
            val wsUrl = uri.getQueryParameter("ws")
            if (wsUrl.isNullOrEmpty()) {
                textView.text = "Invalid QR code!"
                Log.e(TAG, "No ws parameter in URI!")
                return@let
            }

            textView.text = "Connecting to: $wsUrl"
            Log.d(TAG, "Connecting to: $wsUrl")
            connectWebSocket(wsUrl)
        }

        // Send button click
        sendButton.setOnClickListener {
            sendTestData()
        }
    }

    private fun connectWebSocket(wsUrl: String) {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    textView.text = "Connected!"
                }
                Log.d(TAG, "Connected to Unity WebSocket")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    textView.text = "Received: $text"
                }
                Log.d(TAG, "Received: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received bytes: $bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d(TAG, "Closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    textView.text = "Connection failed: ${t.message}"
                }
                t.printStackTrace()
            }
        })
    }

    private fun sendTestData() {
        val msg = "Test message from Android"
        webSocket?.send(msg)
        Log.d(TAG, "Test message sent")
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App destroyed")
    }
}