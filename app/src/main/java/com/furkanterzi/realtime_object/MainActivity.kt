package com.furkanterzi.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.furkanterzi.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.Locale
import kotlin.system.exitProcess
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    // UI bileşenleri
    private lateinit var flashButton: ImageButton
    private lateinit var imageView: ImageView
    private lateinit var textureView: TextureView
    private lateinit var objectTextView: TextView
    private lateinit var alertTextView: TextView
    private lateinit var alertTextView2: TextView
    private lateinit var objectInfoTextView: TextView
    private lateinit var button: Button

    // Model ve kamera
    private lateinit var model: SsdMobilenetV11Metadata1
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraManager: CameraManager

    // İşlem ve medya
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var bitmap: Bitmap
    private lateinit var flashSoundPlayer: MediaPlayer
    private lateinit var permissionSoundPlayer: MediaPlayer
    private lateinit var alertModePlayer: MediaPlayer
    private lateinit var alertModeActive: MediaPlayer
    private lateinit var alertModeDeactive: MediaPlayer
    private lateinit var redMediaPlayer: MediaPlayer
    private lateinit var greenMediaPlayer: MediaPlayer

    private lateinit var handler: Handler

    // Text To Speech
    private lateinit var textToSpeech: TextToSpeech
    private var lastSpokenObject: String? = null
    private var lastSpokenTime: Long = 0L

    private lateinit var tflite: Interpreter


    // Diğer değişkenler
    private var clickCount = 0
    private var lastClickTime: Long = 0
    private var isAlertModeOn = false
    private lateinit var labels: List<String>
    private var isFlashOn = false
    private val paint = Paint().apply {
        textSize = 40f
        strokeWidth = 5f
    }
    private val colors = listOf(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )


    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        // TFLite modelini yükle
        try {
            tflite = Interpreter(loadModelFile())  // Modeli başlat
        } catch (e: Exception) {
            Log.e("TFLite", "Model yüklenirken hata: ${e.message}")
        }

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale("tr", "TR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Türkçe dili desteklenmiyor veya dil verisi eksik.")

                } else {
                    Log.i("TTS", "TextToSpeech başarıyla başlatıldı.")
                }
            } else {
                Log.e("TTS", "TextToSpeech başlatılamadı.")
            }
        }

        alertModePlayer = MediaPlayer.create(this, R.raw.alert_sound)
        Handler(Looper.getMainLooper()).postDelayed({
            alertModePlayer.start()
        }, 2000)


        button = findViewById(R.id.transparentButton)
        button.setOnClickListener { buttonAction() }

        // UI Tanımlamaları
        flashButton = findViewById(R.id.flashButton)
        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)
        objectTextView = findViewById(R.id.detectedObjectLabel)
        alertTextView = findViewById(R.id.alertText)

        // Model ve image processor başlat
        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)

        // Handler thread başlat
        val handlerThread = HandlerThread("videoThread").apply { start() }
        handler = Handler(handlerThread.looper)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Kamera önizleme hazır olduğunda çalışacak listener
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                open_camera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!::model.isInitialized || ContextCompat.checkSelfPermission(this@MainActivity,
                        android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

                bitmap = textureView.bitmap ?: return
                var image = TensorImage.fromBitmap(bitmap)
                image = imageProcessor.process(image)

                val avgBrightness = calculateAverageBrightness(bitmap)
                if (avgBrightness < 20 && !isFlashOn) toggleFlashLight()

                val outputs = model.process(image)
                val locations = outputs.locationsAsTensorBuffer.floatArray
                val classes = outputs.classesAsTensorBuffer.floatArray
                val scores = outputs.scoresAsTensorBuffer.floatArray

                val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)
                val h = mutable.height
                val w = mutable.width
                paint.textSize = h / 15f
                paint.strokeWidth = h / 85f

                scores.forEachIndexed { index, fl ->
                    if (fl > 0.63) {

                        val x = index * 4
                        paint.color = colors[index % colors.size]
                        paint.style = Paint.Style.STROKE
                        canvas.drawRect(
                            RectF(
                                locations[x + 1] * w,
                                locations[x] * h,
                                locations[x + 3] * w,
                                locations[x + 2] * h
                            ), paint
                        )
                        paint.style = Paint.Style.FILL
                        canvas.drawText(
                            "${labels[classes[index].toInt()]} %${"%.0f".format(fl * 100)}",
                            locations[x + 1] * w,
                            locations[x] * h,
                            paint
                        )
                        objectTextView.text = "Tespit Edilen Nesne: ${labels[classes[index].toInt()]}"
                        val top = locations[x] * h
                        val left = locations[x + 1] * w
                        val bottom = locations[x + 2] * h
                        val right = locations[x + 3] * w

                        val boxHeight = bottom - top
                        val boxWidth = right - left
                        val boxArea = boxHeight * boxWidth
                        val imageArea = h * w

                        val currentLabel = labels[classes[index].toInt()]
                        val currentTime = System.currentTimeMillis()

                        if (currentLabel == "Trafik Işığı") {
                            // Trafik ışığının bounding box'ını al
                            val top = locations[x] * h
                            val left = locations[x + 1] * w
                            val bottom = locations[x + 2] * h
                            val right = locations[x + 3] * w

                            // Bounding box içindeki görüntüyü kırp
                            val trafficLightBitmap = Bitmap.createBitmap(
                                bitmap,
                                left.toInt(),
                                top.toInt(),
                                (right - left).toInt(),
                                (bottom - top).toInt()
                            )

                            // TFLite modeline gönder
                            val trafficLightStatus = classifyTrafficLight(trafficLightBitmap)

                            objectInfoTextView = findViewById(R.id.detectedObjectInfo)
                            objectInfoTextView.setText("Trafik Işığı Durumu: $trafficLightStatus")

                            if(trafficLightStatus == "Yeşil"){

                                greenMediaPlayer = MediaPlayer.create(this@MainActivity, R.raw.green_sound)
                                greenMediaPlayer.setOnPreparedListener{
                                    greenMediaPlayer.start()
                                }

                            }
                            else{
                                redMediaPlayer = MediaPlayer.create(this@MainActivity, R.raw.red_sound)
                                redMediaPlayer.setOnPreparedListener{
                                    redMediaPlayer.start()
                                }
                            }

                        }

                        if (isAlertModeOn && boxHeight > h * 0.45f) {
                            if (currentLabel != lastSpokenObject || (currentTime - lastSpokenTime) > 8000) {
                                val alertText = "Yakında $currentLabel var, dikkatli olun"
                                alertTextView2 = findViewById(R.id.alertText2)
                                alertTextView2.setText("Yakında $currentLabel var, dikkatli olun")

                                textToSpeech.speak(alertText, TextToSpeech.QUEUE_FLUSH, null, null)
                                lastSpokenObject = currentLabel
                                lastSpokenTime = currentTime
                            }
                        }
                    }
                }
                imageView.setImageBitmap(mutable)
            }
        }

        // İlk izin kontrolü
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

            get_permission()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        val intent = Intent(this, StartActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    override fun onDestroy() {

        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        if (::tflite.isInitialized) {
            tflite.close() // TFLite Interpreter'ı kapat
        }
        super.onDestroy()
        model.close()


        if (::flashSoundPlayer.isInitialized) {
            flashSoundPlayer.stop()
            flashSoundPlayer.release()
        }
        if (::permissionSoundPlayer.isInitialized) {
            permissionSoundPlayer.stop()
            permissionSoundPlayer.release()
        }

        if (::alertModePlayer.isInitialized) {
            alertModePlayer.stop()
            alertModePlayer.release()
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                open_camera()
            } else {
                objectTextView.text = "Kamera izni gerekli!"
                permissionSoundPlayer = MediaPlayer.create(this, R.raw.permission_sound)
                permissionSoundPlayer.setOnPreparedListener{
                    permissionSoundPlayer.start()
                }
            }
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("traffic_light_cnn.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun classifyTrafficLight(bitmap: Bitmap): String {
        // Görüntüyü modelin beklediği boyuta (64x64) resize et
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)

        // Giriş verisini hazırla (normalize edilmiş float array)
        val inputBuffer = ByteBuffer.allocateDirect(64 * 64 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val pixel = resizedBitmap.getPixel(x, y)
                    // RGB kanallarını normalize et (0-1 arası)
                    putFloat(Color.red(pixel) / 255.0f)
                    putFloat(Color.green(pixel) / 255.0f)
                    putFloat(Color.blue(pixel) / 255.0f)
                }
            }
        }

        // Çıktı buffer'ı hazırla (binary classification: 0-1 arası)
        val outputBuffer = ByteBuffer.allocateDirect(4).apply {
            order(ByteOrder.nativeOrder())
        }

        // Modeli çalıştır
        tflite.run(inputBuffer, outputBuffer)

        // Çıktıyı oku (sigmoid çıktısı)
        outputBuffer.rewind()
        val output = outputBuffer.float
        val threshold = 0.5f // Eşik değeri

        return if (output > threshold) "Kırmızı" else "Yeşil"
    }

    @SuppressLint("MissingPermission")
    fun open_camera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

            get_permission()
            return
        }
        val cameraId = cameraManager.cameraIdList[0]
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surfaceTexture = textureView.surfaceTexture
                val surface = Surface(surfaceTexture)
                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(captureRequest.build(), null, null)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    handler
                )
            }
            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }, handler)
    }

    fun get_permission() {
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 101)
    }
    fun buttonAction(){
        val currentTime = System.currentTimeMillis()

        // Eğer tıklamalar arasında çok kısa bir süre geçtiyse (örneğin 500ms), sayacı artır
        if (currentTime - lastClickTime < 1000) {
            clickCount++
        } else {
            clickCount = 1
        }

        lastClickTime = currentTime

        // 2. tıklamada uyarı modu aç
        if (clickCount == 2) {

            isAlertModeOn = !isAlertModeOn

            if (isAlertModeOn) {
                alertModeActive = MediaPlayer.create(this, R.raw.aktif_sound)
                alertModeActive.setOnPreparedListener{
                    alertModeActive.start()
                }
                alertTextView.text = "Çevresel Uyarı Modu: Aktif"

            } else {
                alertModeDeactive = MediaPlayer.create(this, R.raw.kapali_sound)
                alertModeDeactive.setOnPreparedListener{
                    alertModeDeactive.start()
                }
                alertTextView.text = "Çevresel Uyarı Modu: Kapalı"

            }
        }

        // 3. tıklamada uygulamayı kapat
        if (clickCount == 3) {
            finishAffinity() // Tüm aktiviteleri kapatır
            exitProcess(0)   // Uygulama sürecini sonlandırır
        }
    }

    fun calculateAverageBrightness(bitmap: Bitmap): Int {
        var brightnessSum = 0L
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            brightnessSum += (r + g + b) / 3
        }
        return (brightnessSum / pixels.size).toInt()
    }

    @SuppressLint("MissingPermission")
    fun toggleFlashLight() {
        val cameraId = cameraManager.cameraIdList[0]
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (flashAvailable) {
            val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            if (isFlashOn) {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                flashButton.setImageResource(R.drawable.flashlight2)
                isFlashOn = false
            } else {
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                flashSoundPlayer = MediaPlayer.create(this, R.raw.flashlight_sound)
                flashSoundPlayer.setOnPreparedListener { flashSoundPlayer.start() }
                flashButton.setImageResource(R.drawable.flashlight1)
                isFlashOn = true
            }
            val surfaceTexture = textureView.surfaceTexture
            val surface = Surface(surfaceTexture)
            requestBuilder.addTarget(surface)
            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(requestBuilder.build(), null, null)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, handler)
        }
    }
}