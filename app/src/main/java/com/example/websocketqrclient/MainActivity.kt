package com.example.websocketqrclient

import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okio.ByteString
import java.nio.charset.Charset

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private val TAG = "WebSocketClient"
    private var webSocket: WebSocket? = null
    private lateinit var textView: TextView
    private lateinit var scanButton: Button
    private var nfcAdapter: NfcAdapter? = null

    // --- QR Scanner Result Handler ---
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            textView.text = "Scan cancelled"
        } else {
            // This handles the string scanned from inside the app
            try {
                val uri = Uri.parse(result.contents)
                processUri(uri)
            } catch (e: Exception) {
                textView.text = "Error parsing QR: ${e.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        scanButton = findViewById(R.id.sendButton) // Using your existing button ID

        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            textView.text = "NFC is not supported on this device."
        }

        // 1. Handle Deep Link (When app is opened from System Camera)
        intent?.data?.let { uri ->
            processUri(uri)
        }

        // 2. Handle Manual Scan (When user clicks button inside app)
        scanButton.setOnClickListener {
            startQrScanner()
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Align QR Code within the frame")
        options.setBeepEnabled(true)
        options.setOrientationLocked(false)
        barcodeLauncher.launch(options)
    }

    private fun processUri(uri: Uri) {
        val wsUrl = uri.getQueryParameter("ws")
        if (!wsUrl.isNullOrEmpty()) {
            textView.text = "Connecting to: $wsUrl"
            Log.d(TAG, "Connecting to: $wsUrl")
            connectWebSocket(wsUrl)
        } else {
            textView.text = "Invalid QR code format."
            Log.e(TAG, "No ws parameter in URI: $uri")
        }
    }

    // --- NFC Lifecycle Methods ---
    override fun onResume() {
        super.onResume()
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // --- NFC Tag Discovered Callback ---
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        Log.d(TAG, "NFC Tag Discovered!")

        var cardData = readNdefMessage(tag)

        if (cardData == null) {
            val tagIdBytes = tag.id
            cardData = "UID:" + tagIdBytes.joinToString("") { "%02x".format(it) }
        }

        // Send the data over the websocket
        val success = webSocket?.send(cardData) ?: false

        runOnUiThread {
            if (success) {
                textView.text = "Sent to Unity: $cardData"
            } else {
                textView.text = "Failed to send. Is WebSocket connected?"
            }
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
        // Close existing connection if any
        webSocket?.close(1000, "Opening new connection")

        val client = OkHttpClient()
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    textView.text = "Connected! Ready to scan cards."
                    scanButton.text = "Scan Again" // Change button text once connected
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    textView.text = "Unity says: $text"
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    textView.text = "Connection failed: ${t.message}"
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "App destroyed")
    }
}