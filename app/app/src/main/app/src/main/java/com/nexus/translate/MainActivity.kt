package com.nexus.translate

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val projectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "Çeviriyi Başlat"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
        setContentView(button)
    }
}
