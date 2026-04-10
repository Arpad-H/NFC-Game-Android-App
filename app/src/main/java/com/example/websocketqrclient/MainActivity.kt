package com.example.websocketqrclient

import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import okhttp3.*
import okio.ByteString
import java.nio.charset.Charset

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private val TAG = "WebSocketClient"
    private var webSocket: WebSocket? = null
    private lateinit var textView: TextView
    private lateinit var sendButton: Button

    // Add NFC Adapter
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        sendButton = findViewById(R.id.sendButton)

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            textView.text = "NFC is not supported on this device."
            Log.e(TAG, "NFC not supported")
        }

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

    // --- NFC Lifecycle Methods ---
    override fun onResume() {
        super.onResume()
        // Enable NFC Reader mode while activity is in the foreground
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V

        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        // Disable NFC Reader mode when activity goes to background
        nfcAdapter?.disableReaderMode(this)
    }

    // --- NFC Tag Discovered Callback ---
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        Log.d(TAG, "NFC Tag Discovered!")

        // Strategy 1: Try to read NDEF text payload (if you wrote text to the tags)
        var cardData = readNdefMessage(tag)

        // Strategy 2: If no text is found, grab the unique hardware ID (UID) of the tag
        if (cardData == null) {
            val tagIdBytes = tag.id
            cardData = "UID:" + tagIdBytes.joinToString("") { "%02x".format(it) }
        }

        // Send the data over the websocket
        webSocket?.send(cardData)
        Log.d(TAG, "Sent NFC data: $cardData")

        // Update UI (ReaderCallback runs on a background thread!)
        runOnUiThread {
            textView.text = "Scanned: $cardData"
        }
    }

    private fun readNdefMessage(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null

        try {
            ndef.connect()
            val ndefMessage = ndef.ndefMessage ?: return null
            val records = ndefMessage.records

            if (records.isNotEmpty()) {
                val payload = records[0].payload

                // Decode standard NDEF Text Record
                val textEncoding = if ((payload[0].toInt() and 128) == 0) "UTF-8" else "UTF-16"
                val languageCodeLength = payload[0].toInt() and 51
                return String(
                    payload,
                    languageCodeLength + 1,
                    payload.size - languageCodeLength - 1,
                    Charset.forName(textEncoding)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading NDEF", e)
        } finally {
            try { ndef.close() } catch (e: Exception) {}
        }
        return null
    }

    // --- WebSocket Methods ---
    private fun connectWebSocket(wsUrl: String) {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    textView.text = "Connected! Ready to scan cards."
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