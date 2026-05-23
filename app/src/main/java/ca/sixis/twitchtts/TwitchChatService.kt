package ca.sixis.twitchtts

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.*

class TwitchChatService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val CHANNEL_ID = "TwitchChatChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TwitchTTS::ServiceWakeLock")
        wakeLock?.acquire()
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val channel = intent?.getStringExtra("CHANNEL") ?: ""
        val token = intent?.getStringExtra("TOKEN") ?: ""

        if (channel.isNotEmpty() && token.isNotEmpty()) {
            if (ChatRepository.connectionState.value == ConnectionState.Disconnected || 
                ChatRepository.connectionState.value is ConnectionState.Error) {
                ChatRepository.updateState(ConnectionState.Connecting)
                startForeground(NOTIFICATION_ID, createNotification("Connecting to #$channel..."))
                connectToTwitch(channel, token)
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Twitch Chat TTS"
            val descriptionText = "Notifications for Twitch Chat TTS Service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val stopIntent = Intent(this, TwitchChatService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twitch TTS Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun connectToTwitch(channel: String, token: String) {
        serviceScope.launch {
            var retryCount = 0
            while (isActive) {
                try {
                    socket = Socket("irc.chat.twitch.tv", 6667)
                    writer = PrintWriter(socket!!.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                    // Ensure token starts with oauth:
                    val formattedToken = if (token.startsWith("oauth:")) token else "oauth:$token"

                    writer?.print("PASS $formattedToken\r\n")
                    writer?.print("NICK anonymous\r\n")
                    writer?.print("JOIN #$channel\r\n")
                    writer?.flush()

                    ChatRepository.updateState(ConnectionState.Connected)
                    updateNotification("Connected to #$channel")
                    retryCount = 0 // Reset retries on successful connection

                    while (isActive) {
                        val line = reader?.readLine() ?: break
                        handleIrcLine(line, channel)
                        
                        // Check if auth failed from handleIrcLine
                        val currentState = ChatRepository.connectionState.value
                        if (currentState is ConnectionState.Error && currentState.message.contains("Login failed")) {
                            return@launch // Stop retrying on auth failure
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break

                    retryCount++
                    val waitTime = (retryCount * 5).coerceAtMost(60).toLong()
                    
                    val displayError = "Connection lost. Retrying in ${waitTime}s..."
                    ChatRepository.updateState(ConnectionState.Error(displayError))
                    updateNotification(displayError)
                    
                    delay(waitTime * 1000)
                } finally {
                    socket?.close()
                }
            }
            
            if (isActive) {
                // If we exited the loop but are still active, it was likely a permanent error
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleIrcLine(line: String, channel: String) {
        if (line.startsWith("PING")) {
            writer?.print("PONG :tmi.twitch.tv\r\n")
            writer?.flush()
        } else if (line.contains("NOTICE * :Login authentication failed")) {
            ChatRepository.updateState(ConnectionState.Error("Login failed. Check your OAuth token."))
        } else if (line.contains("PRIVMSG #$channel")) {
            // Example line: :bob!bob@bob.tmi.twitch.tv PRIVMSG #mychannel :hello world
            val user = line.substringAfter(":").substringBefore("!").trim()
            val message = line.substringAfter("PRIVMSG #$channel :").trim()
            
            if (user.isNotEmpty() && message.isNotEmpty() && !line.startsWith("PING")) {
                serviceScope.launch {
                    ChatRepository.addMessage(ChatMessage(user, message))
                    speak("$user said: $message")
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) return

        val result = requestAudioFocus()
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "utteranceId")
        }
    }

    private fun requestAudioFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
            
            focusRequest = request
            audioManager?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        serviceScope.cancel()
        socket?.close()
        tts?.stop()
        tts?.shutdown()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager?.abandonAudioFocusRequest(focusRequest!!)
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        ChatRepository.updateState(ConnectionState.Disconnected)
    }
}
