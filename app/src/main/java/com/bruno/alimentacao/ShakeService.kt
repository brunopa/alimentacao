package com.bruno.alimentacao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastTriggerTime = 0L
    private var shakeThreshold = Prefs.DEFAULT_SENS_INDEX.toFloat()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_SENS_INDEX) {
            shakeThreshold = Prefs.threshold(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        shakeThreshold = Prefs.threshold(this)
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Aceleração total menos a gravidade (~9.81). Quanto maior, mais forte o movimento.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > shakeThreshold) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerTime > COOLDOWN_MS) {
                lastTriggerTime = now
                launchCamera()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun launchCamera() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Detecção de movimento",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "shake_service"
        private const val NOTIF_ID = 1
        private const val COOLDOWN_MS = 3000L
    }
}
