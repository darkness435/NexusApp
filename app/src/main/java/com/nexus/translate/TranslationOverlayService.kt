package com.nexus.translate

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = "SENIN_API_ANAHTARIN_BURAYA")
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var targetLanguage = "Türkçe"
    private var textColor = "#FFFF00"

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val channel = NotificationChannel("nexus", "Nexus", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "nexus")
            .setContentTitle("Nexus Çeviri Aktif")
            .setContentText("Ekran çevirisi arka planda çalışıyor.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
        
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = projManager.getMediaProjection(resultCode, data)
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        mediaProjection.createVirtualDisplay("Capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) translateAndDraw(block.text, block.boundingBox)
                }
                image.close()
            }
        }, null)
        return START_STICKY
    }

    private fun translateAndDraw(text: String, rect: Rect?) {
        if (rect == null) return
        scope.launch {
            try {
                val prompt = "Sen bir oyun ve uygulama çevirmenisin. Lütfen şu metni $targetLanguage diline oyun bağlamını koruyarak çevir: $text"
                val translated = generativeModel.generateContent(prompt).text ?: ""
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP or Gravity.START; x = rect.left; y = rect.bottom }
                
                val textView = TextView(this@TranslationOverlayService).apply {
                    this.text = "($translated)"
                    setTextColor(Color.parseColor(textColor))
                    setBackgroundColor(Color.parseColor("#B3000000")) 
                    setPadding(8, 4, 8, 4)
                    setTypeface(null, Typeface.BOLD)
                }
                windowManager.addView(textView, params)
                delay(3500)
                windowManager.removeView(textView)
            } catch (e: Exception) {}
        }
    }
}
