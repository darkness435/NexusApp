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
    
    // Artık sabit anahtar yok, dinamik olarak oluşturacağız
    private var generativeModel: GenerativeModel? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var targetLanguage = "Türkçe"
    private var textColor = "#FFFF00"
    private var lastTranslateTime = 0L

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
        
        // Arayüzden gelen kullanıcı ayarlarını al
        targetLanguage = intent.getStringExtra("TARGET_LANG") ?: "Türkçe"
        textColor = intent.getStringExtra("TEXT_COLOR") ?: "#FFFF00"
        val userApiKey = intent.getStringExtra("API_KEY") ?: ""

        // Yapay zekayı kullanıcının kendi anahtarıyla başlat
        if (userApiKey.isNotEmpty()) {
            generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = userApiKey)
        }
        
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = projManager.getMediaProjection(resultCode, data)
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        mediaProjection.createVirtualDisplay("Capture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTranslateTime < 6000) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastTranslateTime = currentTime

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width
                
                val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        if (block.text.isNotBlank()) {
                            translateAndDraw(block.text, block.boundingBox)
                            break 
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                image.close()
            }
        }, null)
        
        return START_STICKY
    }

    private fun translateAndDraw(text: String, rect: Rect?) {
        if (rect == null) return
        val model = generativeModel ?: return // Anahtar yoksa iptal et
        
        scope.launch {
            try {
                val prompt = "Sen bir oyun çevirmenisin. Bu metni $targetLanguage diline kısa ve net şekilde çevir: $text"
                val response = model.generateContent(prompt)
                val translated = response.text ?: return@launch
                
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
                    setBackgroundColor(Color.parseColor("#CC000000")) 
                    setPadding(12, 6, 12, 6)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 14f
                }
                
                windowManager.addView(textView, params)
                delay(4000) 
                windowManager.removeView(textView)
            } catch (e: Exception) {
            }
        }
    }
}
