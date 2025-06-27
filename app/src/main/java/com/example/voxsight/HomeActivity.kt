package com.example.voxsight

import android.Manifest
import android.os.Handler
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.telephony.SmsManager
import android.text.format.DateFormat
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.Date
import android.database.Cursor
import android.provider.Telephony
import android.content.ContentValues


class HomeActivity : AppCompatActivity(), OnInitListener {
    private lateinit var micIV: ImageView
    private lateinit var outputTV: TextView
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val SMS_PERMISSION_CODE = 3
    private val READ_SMS_PERMISSION_CODE = 4
    private var isWaitingForRecipient = false
    private var isWaitingForMessage = false
    private var pendingRecipient: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        micIV = findViewById(R.id.mic_speak_iv)
        outputTV = findViewById(R.id.speak_output_tv)

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        micIV.setOnClickListener {
            checkAudioPermission()
        }
    }

    private fun startSpeechToText() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(bundle: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {
                micIV.setColorFilter(
                    ContextCompat.getColor(
                        applicationContext,
                        R.color.mic_disabled_color
                    )
                )
            }

            override fun onError(errorCode: Int) {
                val message = when (errorCode) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Please allow permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No voice detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Already Listening"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    else -> "Unknown error"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }

            override fun onResults(bundle: Bundle) {
                val result = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (result != null && result.isNotEmpty()) {
                    outputTV.text = result[0]
                    processVoiceCommand(result[0].lowercase(Locale.getDefault()))
                }
            }

            override fun onPartialResults(bundle: Bundle) {}
            override fun onEvent(i: Int, bundle: Bundle?) {}
        })
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    private fun processVoiceCommand(command: String) {
        when {
            command.contains("battery") && (command.contains("percentage") || command.contains("level")) -> {
                val batteryLevel = getBatteryPercentage()
                speakOut("Battery level is $batteryLevel percent")
            }
            command.contains("time") -> {
                val time = DateFormat.getTimeFormat(this).format(Date())
                speakOut("Current time is $time")
            }
            command.contains("date") -> {
                val date = DateFormat.getDateFormat(this).format(Date())
                speakOut("Today's date is $date")
            }
            command.contains("read messages")|| command.contains("read message") || command.contains("read my messages") -> {
                Log.d("VoiceCommand", "Read messages command detected")
                checkReadSmsPermission()
            }
            command.contains("send message") || command.contains("text") -> {
                startInteractiveSmsSending()
            }

            isWaitingForRecipient -> {
                processRecipientInput(command)
            }

            isWaitingForMessage -> {
                processMessageInput(command)
            }
            command.contains("check messages") || command.contains("new messages") -> {
                checkForNewMessages()
            }
            command.contains("call") -> {
                val number = extractPhoneNumber(command)
                if (number != null) {
                    makePhoneCall(number)
                } else {
                    speakOut("I couldn't find a phone number in your request")
                }
            }
            else -> {
                speakOut("I didn't understand that command")
            }
        }
    }
    private fun startInteractiveSmsSending() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_CODE
            )
            speakOut("Please grant SMS send permission first")
            return
        }

        speakOut("Please say the recipient's phone number")
        isWaitingForRecipient = true
        isWaitingForMessage = false
        pendingRecipient = null
    }
    private fun processRecipientInput(input: String) {
        val number = extractPhoneNumber(input)
        if (number != null) {
            pendingRecipient = number
            speakOut("You said $number. Now please say your message")
            isWaitingForRecipient = false
            isWaitingForMessage = true
        } else {
            speakOut("I didn't get a valid phone number. Please try again")
        }
    }

    private fun processMessageInput(input: String) {
        pendingRecipient?.let { recipient ->
            sendSms(recipient, input)
            isWaitingForMessage = false
            pendingRecipient = null
        } ?: run {
            speakOut("Error: No recipient found. Please start over")
            isWaitingForMessage = false
        }
    }

    private fun checkReadSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("Permissions", "Requesting READ_SMS permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS),
                READ_SMS_PERMISSION_CODE
            )
            speakOut("Please grant SMS read permission first")
        } else {
            Log.d("Permissions", "READ_SMS permission already granted")
            readSmsMessages()
        }
    }
    private fun readSmsMessages() {
        Log.d("SMS", "Attempting to read SMS messages")

        try {
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val selection = "${Telephony.Sms.READ} = 0" // Unread messages
            val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT 1" // Get only the latest message

            val cursor = contentResolver.query(
                uri,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use {
                if (it.count == 0) {
                    Log.d("SMS", "No unread messages found")
                    speakOut("You have no new messages")
                    return
                }

                if (it.moveToFirst()) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                    val messageId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))

                    val formattedMessage = "From ${formatPhoneNumber(address)}, Message is '$body'"
                    Log.d("SMS", "Latest message: $formattedMessage")

                    speakOut(formattedMessage)
                    markMessageAsRead(messageId)
                }
            } ?: run {
                Log.e("SMS", "Cursor is null")
                speakOut("Could not access your messages")
            }
        } catch (e: SecurityException) {
            Log.e("SMS", "Security Exception", e)
            speakOut("Permission denied to read messages")
        } catch (e: Exception) {
            Log.e("SMS", "Error reading messages", e)
            speakOut("Error reading messages")
        }
    }
    private fun formatPhoneNumber(number: String): String {
        return if (number.matches(Regex("^\\d{10}$"))) {
            "(${number.substring(0, 3)}) ${number.substring(3, 6)}-${number.substring(6)}"
        } else {
            number
        }
    }
    private fun markMessageAsRead(messageId: Long) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, true)
            }
            contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(messageId.toString())
            )
            Log.d("SMS", "Marked message $messageId as read")
        } catch (e: Exception) {
            Log.e("SMS", "Error marking message as read", e)
        }
    }
    private fun processSendSmsCommand(command: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_CODE
            )
            speakOut("Please grant SMS send permission first")
            return
        }

        // Extract phone number and message from command
        val parts = command.split("to", "message").map { it.trim() }
        if (parts.size < 3) {
            speakOut("Please specify both recipient and message content")
            return
        }

        val number = extractPhoneNumber(parts[1])
        val message = parts[2]

        if (number == null) {
            speakOut("I couldn't find a valid phone number")
            return
        }

        sendSms(number, message)
    }

    private fun sendSms(number: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            speakOut("Message sent successfully to $number")
        } catch (e: Exception) {
            speakOut("Failed to send message. ${e.localizedMessage}")
        }
    }
    private fun extractPhoneNumber(command: String): String? {
        // Remove all non-digit characters except '+'
        val digitsOnly = command.replace(Regex("[^0-9+]"), "")

        // Check if it looks like a phone number
        return if (digitsOnly.matches(Regex("^\\+?[0-9]{7,15}$"))) {
            digitsOnly
        } else {
            null
        }
    }
    private fun checkForNewMessages() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            checkReadSmsPermission()
            return
        }

        val cursor: Cursor? = try {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.READ} = ?",
                arrayOf("0"),
                null
            )
        } catch (e: Exception) {
            Log.e("SMS", "Error checking messages", e)
            null
        }

        val unreadCount = cursor?.count ?: 0
        cursor?.close()

        if (unreadCount > 0) {
            speakOut("You have $unreadCount new messages. Say 'read messages' to hear the latest unread message.")
        } else {
            speakOut("You have no new messages")
        }
    }
    private fun getBatteryPercentage(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }
        return batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        } ?: -1
    }



    private fun makePhoneCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            startActivity(callIntent)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 2)
            speakOut("Please grant phone call permission first")
        }
    }

    private fun speakOut(text: String) {
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            // Fallback to Toast if TTS isn't ready
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        } else {
            micIV.setColorFilter(ContextCompat.getColor(this, R.color.mic_enabled_color))
            startSpeechToText()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            1 -> { // Audio permission
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    micIV.setColorFilter(ContextCompat.getColor(this, R.color.mic_enabled_color))
                    startSpeechToText()
                } else {
                    Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                        Toast.makeText(this, "Please allow Microphone permission from Settings", Toast.LENGTH_LONG).show()
                    }
                }
            }
            2 -> { // Call permission
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    speakOut("Phone call permission granted. Please try your call again.")
                } else {
                    speakOut("Phone call permission denied")
                }
            }
            SMS_PERMISSION_CODE -> { // Send SMS permission
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    speakOut("SMS permission granted. Please repeat your message command.")
                } else {
                    speakOut("SMS permission denied")
                }
            }
            READ_SMS_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("Permissions", "READ_SMS permission granted")
                    speakOut("SMS permission granted. Reading messages now.")
                    readSmsMessages()
                } else {
                    Log.d("Permissions", "READ_SMS permission denied")
                    speakOut("Cannot read messages without permission. Please grant SMS permission in settings.")
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.getDefault())
            isTtsInitialized = if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
                false
            } else {
                true
            }
        } else {
            Log.e("TTS", "Initialization failed")
            isTtsInitialized = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        if (isTtsInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
