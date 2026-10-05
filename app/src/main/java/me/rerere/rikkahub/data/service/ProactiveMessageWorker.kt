package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based fallback for proactive message scheduling.
 * More reliable than AlarmManager on devices with aggressive battery optimization.
 */
class ProactiveMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveMessageWorker"
        private const val UNIQUE_WORK_NAME = "proactive_message_work"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val delayMinutes = ProactiveMessageService.computeDelayMinutes(
                setting.minIntervalMinutes,
                setting.maxIntervalMinutes,
            )

            val workRequest = OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )

            // 这里不再写展示用的 next_trigger_time。
            //
            // 之前是「值为 0 时才写一次」，结果 Alarm 通道先写过之后，worker
            // 算出的时间永远显示不出来，两条通道各自排程、界面却只反映其中
            // 一条，用户看到的时间对不上实际触发。现在统一由
            // ProactiveMessageService.scheduleNext 负责写，worker 只排程。

            Log.d(TAG, "Scheduled WorkManager proactive message in $delayMinutes minutes")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled WorkManager proactive message")
        }

        /**
         * Check if exact alarm permission is granted (Android 12+)
         */
        fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        /**
         * Check if app is ignoring battery optimizations
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "ProactiveMessageWorker triggered")

        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val settings = settingsStore.settingsFlow.first()
        val proactiveSetting = settings.proactiveMessageSetting.normalized()

        if (!proactiveSetting.enabled) {
            Log.d(TAG, "Proactive message disabled, skipping")
            return Result.success()
        }

        // Acquire a wake lock for the duration of the work
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProactiveMessage::WorkerWakeLock"
        )
        wakeLock.acquire(5 * 60 * 1000L) // 5 minutes max

        try {
            // Delegate to the existing trigger service logic
            // Start the foreground service which handles the actual AI generation
            val serviceIntent = Intent(applicationContext, ProactiveMessageTriggerService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            // 不在这里排下一次。
            //
            // ProactiveMessageTriggerService 的 finally 已经用 NonCancellable
            // 排过了，这里再排一次会让 work 与 alarm 的时间点错开，并且
            // ExistingWorkPolicy.REPLACE 会把刚生效的排程又替换一遍。
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ProactiveMessageWorker failed", e)
            // 启动前台服务失败时兜底排下一次（此时 TriggerService 没跑起来，
            // 它的 finally 不会执行，不排就永久断了）
            scheduleNext(applicationContext, proactiveSetting)
            return Result.retry()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
