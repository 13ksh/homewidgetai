package com.example.ai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("groq_prefs", Context.MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString("api_key", ""))

        binding.btnSave.setOnClickListener {
            val apiKey = binding.etApiKey.text.toString()
            if (apiKey.isBlank()) {
                Toast.makeText(this, "Please enter an API Key", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("api_key", apiKey).apply()
                Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGetApiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
            startActivity(intent)
        }

        binding.btnAddWidget.setOnClickListener {
            addWidgetToHomeScreen()
        }

        binding.btnDiscord.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/Hv8fqDrWKF"))
            startActivity(intent)
        }
    }

    private fun addWidgetToHomeScreen() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val myProvider = ComponentName(this, GroqWidget::class.java)

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val successCallback = PendingIntent.getBroadcast(
                this, 0, Intent(this, GroqWidget::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
        } else {
            Toast.makeText(this, "Widget pinning is not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
