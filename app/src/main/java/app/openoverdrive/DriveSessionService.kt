package app.openoverdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import core.ble.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DriveSessionService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundInternal(intent.getStringExtra(EXTRA_LABEL) ?: "Car")
            ACTION_DISCONNECT -> scope.launch {
                BleProvider.client.disconnect()
                stopSelf()
            }
            else -> {}
        }
        // Stop service automatically when disconnected
        stateJob?.cancel()
        stateJob = scope.launch {
            BleProvider.client.connectionState.collectLatest {
                if (it is ConnectionState.Disconnected) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundInternal(label: String) {
        createChannel()
        val notification = buildNotification(label)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(label: String): Notification {
        val disconnectIntent = Intent(this, DriveSessionService::class.java).apply { action = ACTION_DISCONNECT }
        val pi = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Driving session")
            .setContentText(label)
            .setOngoing(true)
            .addAction(0, "Disconnect", pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "OpenOverdrive", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "open_overdrive_drive"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "app.openoverdrive.action.START"
        const val ACTION_DISCONNECT = "app.openoverdrive.action.DISCONNECT"
        const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val i = Intent(context, DriveSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            ContextCompat.startForegroundService(context, i)
        }
    }
}

