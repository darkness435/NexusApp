package com.nexus.translate

import android.app.Activity // AppCompatActivity yerine saf Activity kullanıyoruz
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() { // Çökmemesi için Activity sınıfına çevrildi
    private val projectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private lateinit var prefs: SharedPreferences
    
    private var selectedLanguage = "Türkçe"
    private var selectedColor = "#FFFF00" 
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("NexusPrefs", Context.MODE_PRIVATE)
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#090D16")) 
            setPadding(60, 40, 60, 40)
        }

        val titleText = TextView(this).apply {
            text = "NEXUS TRANSLATE"
            textSize = 26f
            setTextColor(Color.parseColor("#38BDF8")) 
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        mainLayout.addView(titleText)

        mainLayout.addView(createLabel("Kişisel API Anahtarınız:"))
        apiKeyInput = EditText(this).apply {
            hint = "Gemini API Anahtarı"
            setText(prefs.getString("API_KEY", "")) 
            setBackgroundColor(Color.WHITE) 
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setPadding(30, 30, 30, 30)
            textSize = 14f
        }
        mainLayout.addView(apiKeyInput)

        val getApiKeyText = TextView(this).apply {
            text = "🔗 Ücretsiz API Anahtarı Almak İçin Tıklayın"
            setTextColor(Color.parseColor("#38BDF8")) 
            textSize = 14f
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, 10, 0, 40)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
            }
        }
        mainLayout.addView(getApiKeyText)

        mainLayout.addView(createLabel("Hedef Çeviri Dili:"))
        val languages = arrayOf("Türkçe", "English", "Español", "中文", "हिन्दी", "العربية", "Português", "Русский", "Français", "Deutsch", "日本語", "한국어")
        val langSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE) 
            setPadding(10, 10, 10, 10)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                    selectedLanguage = languages[pos]
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
        mainLayout.addView(langSpinner)

        mainLayout.addView(Space(this).apply { minimumHeight = 30 })

        mainLayout.addView(createLabel("Çeviri Metin Rengi (Neon):"))
        val colorsName = arrayOf("Neon Sarı", "Saf Beyaz", "Matrix Yeşili", "Siber Mavi", "Neon Pembe", "Elektrik Moru", "Ateş Turuncusu")
        val colorsHex = arrayOf("#FFFF00", "#FFFFFF", "#00FF00", "#00FFFF", "#FF00FF", "#8A2BE2", "#FF5500")
        val colorSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE) 
            setPadding(10, 10, 10, 10)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, colorsName)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                    selectedColor = colorsHex[pos]
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
        mainLayout.addView(colorSpinner)
        
        mainLayout.addView(Space(this).apply { minimumHeight = 60 })

        val startButton = Button(this).apply {
            text = "ÇEVİRİ MOTORUNU BAŞLAT"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setPadding(30, 40, 30, 40)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            
            setOnClickListener {
                val currentKey = apiKeyInput.text.toString().trim()
                if (currentKey.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Lütfen önce API Anahtarınızı girin!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("API_KEY", currentKey).apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(context, "Lütfen 'Üste Gösterme' izni verin.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), 1001)
                }
            }
        }
        mainLayout.addView(startButton)
        setContentView(mainLayout)
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0")) 
            setPadding(0, 15, 0, 5)
            setTypeface(null, Typeface.BOLD)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("TARGET_LANG", selectedLanguage)
                putExtra("TEXT_COLOR", selectedColor)
                putExtra("API_KEY", prefs.getString("API_KEY", "")) 
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish() 
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}
