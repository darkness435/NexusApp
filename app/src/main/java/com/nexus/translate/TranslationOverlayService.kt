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
import android.os.Looper
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
    
    // Yüzer buton için
    private var floatingButtonLayout: FrameLayout? = null
    
    private var targetLanguage = "Türkçe"
    private var textColor = "#FFFF00"
    
    private var isTranslating = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val channel = NotificationChannel("nexus", "Nexus", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        
        val notification = Notification.Builder(this, "nexus")
            .setContentTitle("Nexus Çeviri Hazır")
            .setContentText("Ekranda çeviri yapmak için yüzer butona basın.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        
        // Yüzer butonu ekrana ekle
        createFloatingButton()
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
        
        // Artık VirtualDisplay'i sürekli açık tutmuyoruz, sadece butona basılınca kuracağız.
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingButton() {
        floatingButtonLayout = FrameLayout(this)
        
        val button = TextView(this).apply {
            text = "T"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#38BDF8"))
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 20)
        }
        floatingButtonLayout?.addView(button)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // Butonu ekranda sürükleyebilmek için
        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingButtonLayout, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Eğer çok sürüklenmediyse, tıklama (Click) olarak algıla
                        val Xdiff = (event.rawX - initialTouchX).toInt()
                        val Ydiff = (event.rawY - initialTouchY).toInt()
                        if (Xdiff < 10 && Ydiff < 10) {
                            if (!isTranslating) {
                                button.text = "..." // Çevriliyor efekti
                                button.setBackgroundColor(Color.GRAY)
                                captureAndTranslate(button)
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButtonLayout, params)
    }

    // Yalnızca butona basıldığında ÇALIŞAN, ram dostu tek seferlik ekran yakalama
    private fun captureAndTranslate(button: TextView) {
        if (mediaProjection == null) return
        isTranslating = true

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 1)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay("Capture", 
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)

        imageReader!!.setOnImageAvailableListener({ reader ->
            // Görüntüyü bir kez al ve döngüyü hemen kapat! (OOM Koruması)
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            
            // Okuyucuyu anında durdur, böylece tampon(buffer) dolmaz
            virtualDisplay?.release()
            virtualDisplay = null
            reader.setOnImageAvailableListener(null, null)

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

                // Bitmap'i analiz et
                recognizer.process(InputImage.fromBitmap(cleanBitmap, 0)).addOnSuccessListener { visionText ->
                    scope.launch {
                        for (block in visionText.textBlocks) {
                            if (block.text.length > 2) { 
                                translateAndDraw(block.text, block.boundingBox)
                                // İstiyorsan buradaki break'i kaldırarak ekrandaki TÜM metinleri çevirebilirsin.
                                break 
                            }
                        }
                        
                        // İşlem bitince butonu eski haline getir
                        withContext(Dispatchers.Main) {
                            button.text = "T"
                            button.setBackgroundColor(Color.parseColor("#38BDF8"))
                            isTranslating = false
                            imageReader?.close()
                        }
                    }
                }.addOnFailureListener {
                    // Hata olursa butonu sıfırla
                    button.text = "T"
                    button.setBackgroundColor(Color.parseColor("#38BDF8"))
                    isTranslating = false
                    imageReader?.close()
                }
            } catch (e: Exception) {
                isTranslating = false
                imageReader?.close()
            } finally {
                image.close() 
            }
        }, Handler(Looper.getMainLooper()))
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
                delay(4000) 
                windowManager.removeView(textView)
            }
        } catch (e: Exception) {
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (floatingButtonLayout != null) {
            windowManager.removeView(floatingButtonLayout)
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }
}
