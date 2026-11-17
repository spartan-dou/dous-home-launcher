package io.github.spartandou.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * On a tablet left on its charger, the photo frame comes back whenever the screen goes off (its
 * timeout, the power button, another app left idle): what Tasker's "Lancer Immich" profile did for
 * ImmichFrame. Off the charger, or when Home Assistant turned the screen off ([sleep]), the screen
 * stays off.
 *
 * A foreground service, because only a running app hears the screen going off; it does nothing
 * else (no polling, no wake lock while the screen is on).
 */
class FrameKeeper : Service() {
    private val main = Handler(Looper.getMainLooper())

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            if (System.currentTimeMillis() < quietUntil || !plugged(context)) return
            // A moment for the screen to be really off, then back on the frame
            main.postDelayed(::showFrame, WAKE_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).keepFrame || isTv()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFrame() {
        if (System.currentTimeMillis() < quietUntil) return
        // The activity turns the screen on itself (setTurnScreenOn); the wake lock is for systems
        // that only let an activity do it once the screen is already lit
        try {
            @Suppress("DEPRECATION")
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "launcher:frame")
                .acquire(WAKE_LOCK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "No wake lock", e)
        }
        try {
            startActivity(Intent(this, FrameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the frame", e)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.keeper_channel), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_place)
            .setContentTitle(getString(R.string.keeper_notification))
            .setOngoing(true)
            .build()
    }

    /** Device admin, only to turn the screen off (force-lock) when Home Assistant asks. */
    class Admin : DeviceAdminReceiver()

    companion object {
        private const val TAG = "FrameKeeper"
        private const val CHANNEL = "keeper"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_DELAY_MS = 600L
        private const val WAKE_LOCK_MS = 3_000L
        // How long a screen-off asked for is not undone: the screen goes off well within it
        private const val QUIET_MS = 5_000L

        @Volatile
        private var quietUntil = 0L

        fun plugged(context: Context): Boolean =
            (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        /** Starts or stops the service as the setting says; a tablet only. */
        fun sync(context: Context) {
            val intent = Intent(context, FrameKeeper::class.java)
            try {
                if (Prefs(context).keepFrame && !context.isTv()) context.startForegroundService(intent)
                else context.stopService(intent)
            } catch (e: Exception) {
                // Not allowed from the background on some systems: the next app start does it
                Log.w(TAG, "Cannot start the frame keeper", e)
            }
        }

        fun admin(context: Context) = ComponentName(context, Admin::class.java)

        fun canSleep(context: Context) =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))

        /**
         * Turns the screen off and leaves it off (what Tasker's "Lock" task did on HA_LOCK). False
         * when the app was not made a device admin.
         */
        fun sleep(context: Context): Boolean {
            if (!canSleep(context)) return false
            quietUntil = System.currentTimeMillis() + QUIET_MS
            context.getSystemService(DevicePolicyManager::class.java).lockNow()
            return true
        }
    }
}

/** Starts the frame keeper again after a reboot or an update. */
class KeeperStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> FrameKeeper.sync(context)
        }
    }
}
