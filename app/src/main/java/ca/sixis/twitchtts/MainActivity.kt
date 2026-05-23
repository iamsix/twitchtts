package ca.sixis.twitchtts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private val PREFS_NAME = "TwitchTTSPrefs"
    private val KEY_CHANNEL = "channel"
    private val KEY_TOKEN = "token"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val channelInput = findViewById<TextInputEditText>(R.id.channelInput)
        val tokenInput = findViewById<TextInputEditText>(R.id.tokenInput)
        val connectButton = findViewById<Button>(R.id.connectButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val chatRecyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        val settingsContainer = findViewById<View>(R.id.settingsContainer)

        // Load saved settings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        channelInput.setText(prefs.getString(KEY_CHANNEL, ""))
        tokenInput.setText(prefs.getString(KEY_TOKEN, ""))

        adapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = adapter

        connectButton.setOnClickListener {
            val channel = channelInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()
            if (channel.isNotEmpty() && token.isNotEmpty()) {
                // Save settings
                prefs.edit().putString(KEY_CHANNEL, channel).putString(KEY_TOKEN, token).apply()
                startTwitchService(channel, token)
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, TwitchChatService::class.java))
        }

        lifecycleScope.launch {
            ChatRepository.messages.collect { message ->
                adapter.addMessage(message)
                chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }

        lifecycleScope.launch {
            ChatRepository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        statusText.text = "Status: Disconnected"
                        connectButton.visibility = View.VISIBLE
                        stopButton.visibility = View.GONE
                        settingsContainer.visibility = View.VISIBLE
                    }
                    is ConnectionState.Connecting -> {
                        statusText.text = "Status: Connecting..."
                        connectButton.isEnabled = false
                        stopButton.visibility = View.VISIBLE
                    }
                    is ConnectionState.Connected -> {
                        statusText.text = "Status: Connected"
                        connectButton.visibility = View.GONE
                        stopButton.visibility = View.VISIBLE
                        settingsContainer.visibility = View.GONE
                        connectButton.isEnabled = true
                    }
                    is ConnectionState.Error -> {
                        statusText.text = "Error: ${state.message}"
                        connectButton.visibility = View.VISIBLE
                        stopButton.visibility = View.GONE
                        settingsContainer.visibility = View.VISIBLE
                        connectButton.isEnabled = true
                    }
                }
            }
        }

        checkNotificationPermission()
    }

    private fun startTwitchService(channel: String, token: String) {
        val intent = Intent(this, TwitchChatService::class.java).apply {
            putExtra("CHANNEL", channel)
            putExtra("TOKEN", token)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}
