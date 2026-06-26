package com.bpareja.pomodorotec.pomodoro

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.os.CountDownTimer
import android.appwidget.AppWidgetManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bpareja.pomodorotec.MainActivity
import com.bpareja.pomodorotec.PomodoroReceiver
import com.bpareja.pomodorotec.PomodoroWidgetProvider
import com.bpareja.pomodorotec.R
import com.bpareja.pomodorotec.utils.DataSyncManager

enum class Phase {
    FOCUS, BREAK
}

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {

    init {
        instance = this
    }

    companion object {
        internal var instance: PomodoroViewModel? = null

        fun skipBreak() {
            instance?.startFocusSession()
        }
    }

    private val context = getApplication<Application>().applicationContext

    /*
     * Para evidencias del laboratorio se usan tiempos cortos:
     * Concentración: 15 segundos
     * Descanso: 8 segundos
     *
     * Si deseas volver al Pomodoro real, cambia true por false.
     */
    private val demoMode = true

    private val focusDurationMillis: Long =
        if (demoMode) 15 * 1000L else 25 * 60 * 1000L

    private val breakDurationMillis: Long =
        if (demoMode) 8 * 1000L else 5 * 60 * 1000L

    private val initialFocusText: String =
        if (demoMode) "00:15" else "25:00"

    private val initialBreakText: String =
        if (demoMode) "00:08" else "05:00"

    // Estados observables para la interfaz
    private val _timeLeft = MutableLiveData(initialFocusText)
    val timeLeft: LiveData<String> = _timeLeft

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _currentPhase = MutableLiveData(Phase.FOCUS)
    val currentPhase: LiveData<Phase> = _currentPhase

    private val _isSkipBreakButtonVisible = MutableLiveData(false)
    val isSkipBreakButtonVisible: LiveData<Boolean> = _isSkipBreakButtonVisible

    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress

    // Variables de control del temporizador
    private var countDownTimer: CountDownTimer? = null
    private var totalTimeInMillis: Long = focusDurationMillis
    private var timeRemainingInMillis: Long = focusDurationMillis

    // ---------------- FUNCIONES PRINCIPALES ----------------

    fun startFocusSession() {
        countDownTimer?.cancel()

        _currentPhase.value = Phase.FOCUS
        timeRemainingInMillis = focusDurationMillis
        totalTimeInMillis = focusDurationMillis
        _timeLeft.value = initialFocusText
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = false

        updateWidgetData()

        showNotification(
            title = "Pomodoro iniciado 🍅",
            message = "Tu sesión de concentración empezó. Mantente enfocado y evita distracciones."
        )

        startTimer()
    }

    private fun startBreakSession() {
        countDownTimer?.cancel()

        _currentPhase.value = Phase.BREAK
        timeRemainingInMillis = breakDurationMillis
        totalTimeInMillis = breakDurationMillis
        _timeLeft.value = initialBreakText
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = true

        updateWidgetData()

        showNotification(
            title = "Descanso activo ☕",
            message = "Terminaste una sesión de concentración. Toma unos minutos para relajarte."
        )

        startTimer()
    }

    fun startTimer() {
        countDownTimer?.cancel()
        _isRunning.value = true

        countDownTimer = object : CountDownTimer(timeRemainingInMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemainingInMillis = millisUntilFinished

                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60

                _timeLeft.value = String.format("%02d:%02d", minutes, seconds)

                val progressValue =
                    1f - (millisUntilFinished.toFloat() / totalTimeInMillis.toFloat())

                _progress.value = progressValue.coerceIn(0f, 1f)

                updateWidgetData()
            }

            override fun onFinish() {
                _isRunning.value = false
                _progress.value = 1f
                _timeLeft.value = "00:00"

                updateWidgetData()

                when (_currentPhase.value) {
                    Phase.FOCUS -> startBreakSession()
                    Phase.BREAK -> startFocusSession()
                    null -> {}
                }
            }
        }.start()
    }

    fun updateDurations(sessionDuration: Int, breakDuration: Int) {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = sessionDuration,
            breakDuration = breakDuration
        )
    }

    fun updateTimerData() {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = 25,
            breakDuration = 5
        )
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        _isRunning.value = false

        showNotification(
            title = "Pomodoro pausado ⏸️",
            message = "El temporizador fue pausado. Puedes reanudarlo cuando estés listo."
        )

        updateWidgetData()
    }

    fun resetTimer() {
        countDownTimer?.cancel()

        _isRunning.value = false
        _currentPhase.value = Phase.FOCUS
        timeRemainingInMillis = focusDurationMillis
        totalTimeInMillis = focusDurationMillis
        _timeLeft.value = initialFocusText
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = false

        updateWidgetData()
    }

    // ---------------- ACTUALIZACIÓN DEL WIDGET ----------------

    private fun updateWidgetData() {
        val phaseText = when (_currentPhase.value) {
            Phase.FOCUS -> "Concentración"
            Phase.BREAK -> "Descanso"
            null -> "Concentración"
        }

        val widgetProgress =
            ((1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())) * 100)
                .toInt()
                .coerceIn(0, 100)

        val prefs = context.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)

        prefs.edit().apply {
            putString("phase", phaseText)
            putString("timeLeft", _timeLeft.value ?: initialFocusText)
            putInt("progress", widgetProgress)
            apply()
        }

        val intent = Intent(context, PomodoroWidgetProvider::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE

        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PomodoroWidgetProvider::class.java))

        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }

    // ---------------- NOTIFICACIÓN PERSONALIZADA ----------------

    private fun showNotification(title: String, message: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val formattedTime =
            _timeLeft.value?.takeIf { it != "00:00" } ?: "Finalizado"

        val phaseMessage = when (_currentPhase.value) {
            Phase.FOCUS -> "Sigue avanzando, una tarea a la vez."
            Phase.BREAK -> "Respira, descansa y prepárate para continuar."
            null -> "Continúa usando la técnica Pomodoro."
        }

        val customMessage = "$message\n⏰ Tiempo: $formattedTime\n$phaseMessage"

        val bigImage = BitmapFactory.decodeResource(
            context.resources,
            if (_currentPhase.value == Phase.FOCUS) {
                R.drawable.focus_image
            } else {
                R.drawable.break_image
            }
        )

        val style = NotificationCompat.BigPictureStyle()
            .bigPicture(bigImage)

        val notificationColor = if (_currentPhase.value == Phase.FOCUS) {
            Color.rgb(178, 34, 34)
        } else {
            Color.rgb(46, 139, 87)
        }

        val vibrationPattern = if (_currentPhase.value == Phase.FOCUS) {
            longArrayOf(0, 120, 100, 120)
        } else {
            longArrayOf(0, 400, 250)
        }

        val pauseIntent = Intent(context, PomodoroReceiver::class.java).apply {
            action = "PAUSE_TIMER"
        }

        val pausePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            pauseIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(context, PomodoroReceiver::class.java).apply {
            action = "RESUME_TIMER"
        }

        val resumePendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            resumeIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val skipIntent = Intent(context, PomodoroReceiver::class.java).apply {
            action = "SKIP_BREAK"
        }

        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            skipIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(context, PomodoroReceiver::class.java).apply {
            action = "END_TIMER"
        }

        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            4,
            endIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val progress =
            ((1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())) * 100)
                .toInt()
                .coerceIn(0, 100)

        val builder = NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
            .setSmallIcon(
                if (_currentPhase.value == Phase.FOCUS) {
                    R.drawable.baseline_center_focus_strong_24
                } else {
                    R.drawable.baseline_free_breakfast_24
                }
            )
            .setContentTitle(title)
            .setContentText(customMessage)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(notificationColor)
            .setColorized(true)
            .setLights(notificationColor, 1000, 1000)
            .setVibrate(vibrationPattern)
            .setProgress(100, progress, false)
            .setSound(
                RingtoneManager.getDefaultUri(
                    if (_currentPhase.value == Phase.FOCUS) {
                        RingtoneManager.TYPE_RINGTONE
                    } else {
                        RingtoneManager.TYPE_NOTIFICATION
                    }
                )
            )
            .addAction(
                R.drawable.baseline_pause_circle_24,
                "Pausar",
                pausePendingIntent
            )
            .addAction(
                R.drawable.ic_resume,
                "Reanudar",
                resumePendingIntent
            )
            .addAction(
                R.drawable.ic_stop,
                "Terminar",
                endPendingIntent
            )

        if (_currentPhase.value == Phase.BREAK) {
            builder.addAction(
                R.drawable.ic_skip,
                "Saltar descanso",
                skipPendingIntent
            )
        }

        with(NotificationManagerCompat.from(context)) {
            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(MainActivity.NOTIFICATION_ID, builder.build())
            }
        }
    }
}