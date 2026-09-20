package com.nexus.translate

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
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
    
    private var generativeModel: GenerativeModel? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var targetLanguage = "Türkçe"
    private var textColor = "#FFFF00"
    private var lastTranslateTime = 0L
    
    // RAM tasarrufu için ekranı %50 küçültüyoruz
    private val scaleDownRatio = 0.5f 
    private val multiplier = 2 // Çeviriyi ekrana çizerken gerçek boyuta döndürmek için

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
            
        // Android 14 Çökme Engelleyici Kod:
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
        val mediaProjection = projManager.getMediaProjection(resultCode, data)
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Cihazın bellek taşmasını önlemek için boyutları yarıya düşürüyoruz
        val scaledWidth = (metrics.widthPixels * scaleDownRatio).toInt()
        val scaledHeight = (metrics.heightPixels * scaleDownRatio).toInt()
        
        val imageReader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2)
        mediaProjection.createVirtualDisplay("Capture", scaledWidth, scaledHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTranslateTime < 5000) { // Her 5 Saniyede Bir İşlem Yap (API Spam Koruması)
                image.close()
                return@setOnImageAvailableListener
            }
            lastTranslateTime = currentTime

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * scaledWidth
                
                // Tam boyuttaki Bitmap'i oluştur (Padding dahil)
                val fullBitmap = Bitmap.createBitmap(scaledWidth + rowPadding / pixelStride, scaledHeight, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                // Bozuk/siyah çeviriyi engellemek için sadece saf ekran görüntüsünü (Padding hariç) kesip alıyoruz
                val cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, scaledWidth, scaledHeight)

                recognizer.process(InputImage.fromBitmap(cleanBitmap, 0)).addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        if (block.text.length > 2) { // 2 harften küçük çerçöp yazıları çevirme
                            translateAndDraw(block.text, block.boundingBox)
                            break 
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                image.close() // Bellek sızıntısını önler
            }
        }, null)
        
        return START_STICKY
    }

    private fun translateAndDraw(text: String, rect: Rect?) {
        if (rect == null) return
        val model = generativeModel ?: return
        
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
                    // Görüntüyü %50 küçülttüğümüz için, koordinatları 2 ile çarpıp ekrandaki gerçek yerine koyuyoruz
                    x = (rect.left * multiplier).coerceAtLeast(20)
                    y = (rect.bottom * multiplier).coerceAtLeast(20)
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
                delay(4000) 
                windowManager.removeView(textView)
            } catch (e: Exception) {
            }
        }
    }
}
