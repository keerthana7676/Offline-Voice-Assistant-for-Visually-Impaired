package com.example.voxsight

import android.Manifest
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

class HomeActivity : AppCompatActivity(), OnInitListener {
    private lateinit var micIV: ImageView
    private lateinit var outputTV: TextView
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false

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
            command.contains("messages") || command.contains("sms") -> {
                // Note: Reading actual messages would require additional permissions
                speakOut("You have no new messages")
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

    private fun extractPhoneNumber(command: String): String? {
        // Simple extraction - in a real app you'd want more sophisticated parsing
        return command.replace("call", "").trim()
            .takeIf { it.matches(Regex("^[0-9+ ]+\$")) }
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
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.getDefault())
            isTtsInitialized = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                false
            } else {
                true
            }
        } else {
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
