package com.nexus.translate

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*

class TranslationOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(Dispatchers.IO + Job()) 
    
    private var generativeModel: GenerativeModel? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // ANR (Çökme) sorununu kökünden çözen Arka Plan İşlemcisi
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var targetLanguage = "Türkçe"
    private var textColor = "#FFFF00"
    private var lastTranslateTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // İşletim sisteminin uygulamanın fişini çekmesini engelleyen Arka Plan Motorunu çalıştırıyoruz
        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val channel = NotificationChannel("nexus", "Nexus", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "nexus")
            .setContentTitle("Nexus Çeviri Aktif")
            .setContentText("Ekran çevirisi arka planda çalışıyor.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("DATA") ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
        
        targetLanguage = intent.getStringExtra("TARGET_LANG") ?: "Türkçe"
        textColor = intent.getStringExtra("TEXT_COLOR") ?: "#FFFF00"
        val userApiKey = intent.getStringExtra("API_KEY") ?: ""

        if (userApiKey.isNotEmpty()) {
            generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = userApiKey)
        }
        
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(resultCode, data)
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay("Capture", 
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)

        // DİKKAT: Artık okuma işlemi ana ekranda değil, backgroundHandler (arka plan) üzerinden yapılıyor!
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTranslateTime < 5000) { 
                image.close()
                return@setOnImageAvailableListener
            }
            lastTranslateTime = currentTime

            try {
                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                val fullBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)

                recognizer.process(InputImage.fromBitmap(cleanBitmap, 0)).addOnSuccessListener { visionText ->
                    scope.launch {
                        for (block in visionText.textBlocks) {
                            if (block.text.length > 2) { 
                                translateAndDraw(block.text, block.boundingBox)
                                break 
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                image.close() 
            }
        }, backgroundHandler)
        
        return START_STICKY
    }

    private suspend fun translateAndDraw(text: String, rect: Rect?) {
        if (rect == null) return
        val model = generativeModel ?: return
        
        try {
            val prompt = "Sen oyun çevirmenisin. Metni $targetLanguage diline çevir. (Sadece çeviriyi yaz): $text"
            val response = model.generateContent(prompt)
            val translated = response.text ?: return
            
            withContext(Dispatchers.Main) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
                ).apply { 
                    gravity = Gravity.TOP or Gravity.START 
                    x = rect.left.coerceAtLeast(20)
                    y = rect.bottom.coerceAtLeast(20)
                }
                
                val textView = TextView(this@TranslationOverlayService).apply {
                    this.text = " $translated "
                    setTextColor(Color.parseColor(textColor))
                    setBackgroundColor(Color.parseColor("#E6000000")) 
                    setPadding(16, 8, 16, 8)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 15f
                }
                
                windowManager.addView(textView, params)
                delay(4500) 
                windowManager.removeView(textView)
            }
        } catch (e: Exception) {
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        backgroundThread?.quitSafely()
    }
}
