package com.example.websocketqrclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import okhttp3.*
import okio.ByteString
import java.nio.charset.Charset
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private val PREFS_NAME = "UserPrefs"
    private val KEY_USERNAME = "username"
    private var username: String? = null
    private val TAG = "WebSocketClient"
    private var webSocket: WebSocket? = null
    private lateinit var textView: TextView
    private lateinit var scanButton: Button
    private var nfcAdapter: NfcAdapter? = null

    // --- Select Element Methods ---
    val selectedElements = mutableListOf<String>()
    val maxSelection = 3
    private lateinit var grid: GridLayout
    private lateinit var statusText: TextView
    private lateinit var displayNameText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)
        scanButton = findViewById(R.id.sendButton)
        grid = findViewById(R.id.elementGrid)
        statusText = findViewById(R.id.selectedElementsText)
        displayNameText = findViewById(R.id.displayNameText)
        val editButton = findViewById<Button>(R.id.editNameButton)
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            textView.text = "NFC is not supported on this device."
        }

        // Only process the intent if it hasn't been handled yet
        if (savedInstanceState == null) {
            intent?.data?.let { uri ->
                processUri(uri)
            }
        }

        // 2. Handle Manual Scan (When user clicks button inside app)
        scanButton.setOnClickListener {
            startQrScanner()
        }

        setupElementGrid()

        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedName = sharedPreferences.getString(KEY_USERNAME, "Guest")
        displayNameText.text = "User: $savedName"

        // 2. Show Popup on click
        editButton.setOnClickListener {
            showEditNameDialog()
        }
    }
    private fun setupElementGrid() {
        val grid = findViewById<GridLayout>(R.id.elementGrid)
        val statusText = findViewById<TextView>(R.id.selectedElementsText)

        // Loop through all children inside the GridLayout
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)

            // Check if the child is a Button before casting
            if (child is Button) {
                child.setOnClickListener {
                    handleElementClick(child, statusText)
                }
            }
        }
    }
    private fun showEditNameDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter Username")

        // Create the input field for the popup
        val input = EditText(this)
        input.hint = "Name"

        // Pre-fill with current name (removing the "User: " prefix)
        val currentName = displayNameText.text.toString().replace("User: ", "")
        input.setText(currentName)

        builder.setView(input)

        // Confirm Button
        builder.setPositiveButton("Confirm") { _, _ ->
            val newName = input.text.toString()
            saveName(newName)
        }

        // Cancel Button
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }
    private fun saveName(name: String) {
        // Update UI
        displayNameText.text = "User: $name"

        // Save to SharedPreferences
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(KEY_USERNAME, name)
        editor.apply()
    }
    private fun handleElementClick(button: Button, statusText: TextView) {
        val element = button.text.toString()

        if (button.isActivated) {
            // Deselect: If it was green, make it grey
            button.isActivated = false
            selectedElements.remove(element)
        } else {
            // Select: If under limit, make it green
            if (selectedElements.size < maxSelection) {
                button.isActivated = true
                selectedElements.add(element)
            } else {
                // Feedback when they try to select a 4th item
                Toast.makeText(this, "Maximum 3 elements allowed", Toast.LENGTH_SHORT).show()
            }
        }

        // Update the display text box
        if (selectedElements.isEmpty()) {
            statusText.text = "Selected: None"
        } else {
            statusText.text = "Selected: ${selectedElements.joinToString(", ")}"
        }
        sendElementsToUnity()
    }
    private fun sendElementsToUnity() {
        // Check if websocket is connected
        if (webSocket != null) {
            // Create a comma-separated string of the elements
            val message = "SELECT_ELEMENTS:${selectedElements.joinToString(",")}"

            // Send it!
            val success = webSocket?.send(message) ?: false

            if (!success) {
                Log.e(TAG, "Failed to send elements. Socket might be closed.")
            }
        }
    }
    private fun startQrScanner() {
        // Configure the scanner to only look for QR codes (makes it even faster)
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        // Get the scanner client
        val scanner = GmsBarcodeScanning.getClient(this, options)

        // Launch the scanner
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                // This triggers when a QR code is successfully scanned
                val rawValue = barcode.rawValue
                if (rawValue != null) {
                    try {
                        val uri = Uri.parse(rawValue)
                        processUri(uri)
                    } catch (e: Exception) {
                        textView.text = "Error parsing QR: ${e.message}"
                    }
                }
            }
            .addOnCanceledListener {
                // This triggers if the user backs out without scanning
                textView.text = "Scan cancelled"
            }
            .addOnFailureListener { e ->
                // This triggers if something goes wrong
                textView.text = "Error scanning: ${e.message}"
                Log.e(TAG, "Barcode scanning failed", e)
            }
    }

    private fun processUri(uri: Uri) {
        val wsUrl = uri.getQueryParameter("ws")
        if (!wsUrl.isNullOrEmpty()) {
            // Log to see if this is being called multiple times unexpectedly
            Log.d(TAG, "Processing URI: $wsUrl")

            // Update UI on the main thread
            runOnUiThread {
                textView.text = "Connecting to: $wsUrl"
            }

            connectWebSocket(wsUrl)
        } else {
            runOnUiThread {
                textView.text = "Invalid QR code format."
            }
        }
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        // Update the Activity's current intent so intent?.data still works later if needed
        setIntent(intent)

        // Handle the new deep link
        intent?.data?.let { uri ->
            Log.d(TAG, "Received new deep link: $uri")
            processUri(uri)
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