package com.example.voxsight

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.os.Handler
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp // Corrected import
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class CurrencyDetectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var detectionResultTextView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private var interpreter: Interpreter? = null

    // For Currency Classification:
    private lateinit var labels: List<String>
    private var inputSize: Int = 0 // Will be determined by model
    private var isSpeaking = false // Flag to prevent rapid speech feedback

    // Constants
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val MODEL_PATH = "currency_detector.tflite" // Your TFLite model file name
    private val LABEL_PATH = "labels.txt" // Your labels file (e.g., 10_rupee, 20_rupee, etc.)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("CurrencyDetection", "CurrencyDetectionActivity: onCreate started")
        setContentView(R.layout.activity_currency_detection)
        previewView = findViewById(R.id.viewFinder)
        detectionResultTextView = findViewById(R.id.detectionResultTextView)
        val stopButton = findViewById<TextView>(R.id.stopDetectionButton)

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        stopButton.setOnClickListener {
            speakOut("Stopping currency detection.")
            // Give TTS a moment to speak before finishing the activity
            Handler(mainLooper).postDelayed({
                finish()
            }, 500) // Adjust delay as needed
        }

        // Load your model and labels FIRST, before starting camera
        try {
            // Load labels
            labels = FileUtil.loadLabels(this, LABEL_PATH)
            Log.d("CurrencyDetection", "Labels loaded: $labels")

            // Load model and get input size
            val modelByteBuffer = FileUtil.loadMappedFile(this, MODEL_PATH)
            val options = Interpreter.Options()
            // Optional: Enable GPU acceleration if available
            // val gpuDelegate = GpuDelegate()
            // options.addDelegate(gpuDelegate)
            interpreter = Interpreter(modelByteBuffer, options)

            // Get input tensor shape (e.g., [1, 224, 224, 3])
            val inputShape = interpreter!!.getInputTensor(0).shape()
            inputSize = inputShape[1] // Assuming height and width are the same and at index 1 and 2

            Log.d("CurrencyDetection", "Model loaded successfully. Input size: $inputSize")

        } catch (e: IOException) {
            Log.e("CurrencyDetection", "Error loading model or labels: ${e.message}", e)
            speakOut("Error initializing currency detector. Please restart the app.")
            Toast.makeText(this, "Error loading detector. See logs.", Toast.LENGTH_LONG).show()
            finish() // Finish if model fails to load
            return // Prevent further execution if model loading fails
        }

        // Now that model is loaded, check permissions and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var imageAnalysis: ImageAnalysis

    private fun startCamera() {
        Log.d("CurrencyDetection", "startCamera() called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // ImageAnalysis
            val imageAnalyzer = CurrencyImageAnalyzer(
                interpreter!!,
                labels,
                inputSize
            ) { detectedCurrency -> // Renamed `detections` to `detectedCurrency` for clarity
                // Callback from the analyzer with the detected currency and confidence
                if (detectedCurrency.isNotEmpty() && !isSpeaking) { // Use isSpeaking flag
                    val (label, confidence) = detectedCurrency.first() // Assuming only one dominant currency
                    if (confidence >= 0.70f) { // Adjusted threshold for speaking, tune as needed
                        val confidencePercent = String.format("%.0f", confidence * 100)
                        val spokenText = "$label. Confidence: ${confidencePercent} percent."
                        Log.d("CurrencyDetection", spokenText)
                        runOnUiThread {
                            detectionResultTextView.text = spokenText
                        }
                        speakOut(label) // Speak only the amount
                        isSpeaking = true // Set flag to true
                        // Reset flag after a delay to allow for next speech feedback
                        Handler(mainLooper).postDelayed({
                            isSpeaking = false
                        }, 3000) // 3 seconds delay before next speech feedback
                    }
                } else if (detectedCurrency.isEmpty() && !isSpeaking) { // Only update if no detection and not speaking
                    runOnUiThread {
                        detectionResultTextView.text = "Searching for currency..."
                    }
                }
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(inputSize, inputSize))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, imageAnalyzer)
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                Log.d("CurrencyDetection", "Camera use cases bound to lifecycle.")

            } catch (exc: Exception) {
                Log.e("CurrencyDetection", "Use case binding failed", exc)
                speakOut("Failed to start camera for detection. Please check permissions.")
                Toast.makeText(this, "Camera error. See logs.", Toast.LENGTH_LONG).show()
                finish()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission not granted. Cannot perform currency detection.",
                    Toast.LENGTH_SHORT
                ).show()
                speakOut("Camera permission not granted. Cannot perform currency detection.")
                finish()
            }
        }
    }

    private class CurrencyImageAnalyzer(
        private val interpreter: Interpreter,
        private val labels: List<String>,
        private val inputSize: Int,
        // The listener now expects a single Pair (label, confidence) or an empty list if nothing detected
        private val listener: (List<Pair<String, Float>>) -> Unit
    ) : ImageAnalysis.Analyzer {

        private fun ImageProxy.toBitmap(): Bitmap? {
            if (planes == null || planes.size < 3) {
                Log.e("CurrencyDetection", "ImageProxy has invalid planes count: ${planes?.size}")
                return null
            }
            try {
                val yBuffer = planes[0].buffer
                val uBuffer = planes[1].buffer
                val vBuffer = planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = android.graphics.YuvImage(
                    nv21,
                    android.graphics.ImageFormat.NV21,
                    this.width,
                    this.height,
                    null
                )
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(
                    android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
                    75,
                    out
                )
                val imageBytes = out.toByteArray()
                return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } catch (e: Exception) {
                Log.e("CurrencyDetection", "Error converting ImageProxy to Bitmap: ${e.message}", e)
                return null
            }
        }

        override fun analyze(imageProxy: ImageProxy) {
            Log.d("CurrencyDetection", "ImageAnalyzer: analyze() called - received new frame!")
            Log.d("CurrencyDetection", "Analyzer state: interpreter=${interpreter != null}, labels=${labels != null}, inputSize=$inputSize")

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()

            if (bitmap == null) {
                Log.e("CurrencyDetection", "Skipping analysis: Bitmap is null.")
                imageProxy.close()
                return
            }

            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
            Log.d("CurrencyDetection", "Bitmap rotated. Original size: ${bitmap.width}x${bitmap.height}, Rotated size: ${rotatedBitmap.width}x${rotatedBitmap.height}")

            val imageTensor = TensorImage(DataType.FLOAT32)
            imageTensor.load(rotatedBitmap)

            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(127.5f, 127.5f)) // Normalize to [-1, 1] for MobileNet-like models
                .build()
            val processedImage = imageProcessor.process(imageTensor)
            // Corrected logging for DataType
//            Log.d("CurrencyDetection", "Image processed for model. Tensor size: ${processedImage.width}x${processedImage.height}, DataType: ${processedImage.dataType().toString()}")


            // === MODIFIED FOR CLASSIFICATION MODEL OUTPUT ===
            // Assume your model outputs probabilities for each class at index 0
            val numClasses = labels.size
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, numClasses), DataType.FLOAT32)

            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = outputBuffer.buffer // Assuming the single output is at index 0

            try {
                interpreter.runForMultipleInputsOutputs(arrayOf(processedImage.buffer), outputs)
                Log.d("CurrencyDetection", "Interpreter ran successfully for classification.")
            } catch (e: Exception) {
                Log.e("CurrencyDetection", "Error running interpreter: ${e.message}", e)
                imageProxy.close()
                return
            }

            val probabilities = outputBuffer.floatArray
            var maxConfidence = 0.0f
            var detectedLabel = "" // Initialize to empty string
            var maxConfidenceIndex = -1

            // Find the class with the highest probability
            for (i in probabilities.indices) {
                if (probabilities[i] > maxConfidence) {
                    maxConfidence = probabilities[i]
                    maxConfidenceIndex = i
                }
            }

            val detectedResults = mutableListOf<Pair<String, Float>>()
            val CLASSIFICATION_THRESHOLD = 0.7f // Tune this threshold for when to consider a detection valid

            if (maxConfidence >= CLASSIFICATION_THRESHOLD && maxConfidenceIndex != -1 && maxConfidenceIndex < labels.size) {
                detectedLabel = labels[maxConfidenceIndex]
                detectedResults.add(Pair(detectedLabel, maxConfidence))
                Log.d("CurrencyDetection", "Classification Detected: $detectedLabel, Confidence: $maxConfidence")
            } else {
                Log.d("CurrencyDetection", "No significant classification detected. Max confidence: $maxConfidence")
            }

            listener(detectedResults) // Send results back to the activity

            imageProxy.close() // VERY IMPORTANT: Close the imageProxy to release the buffer
            Log.d("CurrencyDetection", "ImageProxy closed.")
        }

        private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
            if (degrees == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
            Log.e("TTS", "TTS Initialization failed")
            isTtsInitialized = false
        }
        if (isTtsInitialized) {
            Handler(mainLooper).postDelayed({
                speakOut("Currency detection started. Point your camera at a note.")
            }, 1000)
        }
    }

    private fun speakOut(text: String) {
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CurrencyDetection", "CurrencyDetectionActivity: onDestroy started.")
        cameraExecutor.shutdown()
        try {
            if (!cameraExecutor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                Log.w("CurrencyDetection", "Camera executor did not terminate in time.")
            }
        } catch (e: InterruptedException) {
            Log.e("CurrencyDetection", "Camera executor termination interrupted.", e)
            Thread.currentThread().interrupt()
        }

        interpreter?.close()
        if (isTtsInitialized) {
            tts.stop()
            tts.shutdown()
        }
        Log.d("CurrencyDetection", "CurrencyDetectionActivity: onDestroy completed.")
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
