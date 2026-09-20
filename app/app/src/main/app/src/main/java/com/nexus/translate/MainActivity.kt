package com.nexus.translate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val projectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    
    private var selectedLanguage = "Türkçe"
    private var selectedColor = "#FFFF00" 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            val mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0F172A")) 
                setPadding(60, 60, 60, 60)
            }

            val titleText = TextView(this).apply {
                text = "NEXUS TRANSLATE"
                textSize = 28f
                setTextColor(Color.parseColor("#38BDF8")) 
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 80)
            }
            mainLayout.addView(titleText)

            val langLabel = createLabel("Hedef Çeviri Dili:")
            val languages = arrayOf("Türkçe", "English", "Español", "中文", "العربية", "Русский", "Français", "Deutsch", "日本語", "한국어")
            val langSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                        selectedLanguage = languages[pos]
                    }
                    override fun NothingSelected(parent: AdapterView<*>) {}
                }
            }
            mainLayout.addView(langLabel)
            mainLayout.addView(langSpinner)

            val colorLabel = createLabel("Çeviri Metni Rengi:")
            val colorsName = arrayOf("Neon Sarı", "Saf Beyaz", "Matrix Yeşili", "Siber Mavi", "Neon Pembe", "Elektrik Moru", "Ateş Turuncusu")
            val colorsHex = arrayOf("#FFFF00", "#FFFFFF", "#00FF00", "#00FFFF", "#FF00FF", "#8A2BE2", "#FF5500")
            val colorSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, colorsName)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                        selectedColor = colorsHex[pos]
                    }
                    override fun NothingSelected(parent: AdapterView<*>) {}
                }
            }
            mainLayout.addView(colorLabel)
            mainLayout.addView(colorSpinner)
            
            mainLayout.addView(Space(this).apply { minimumHeight = 100 })

            val startButton = Button(this).apply {
                text = "ÇEVİRİ MOTORUNU BAŞLAT"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2563EB"))
                setPadding(30, 40, 30, 40)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                
                setOnClickListener {
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
        } catch (e: Exception) {
            Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 40, 0, 10)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("TARGET_LANG", selectedLanguage)
                putExtra("TEXT_COLOR", selectedColor)
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
