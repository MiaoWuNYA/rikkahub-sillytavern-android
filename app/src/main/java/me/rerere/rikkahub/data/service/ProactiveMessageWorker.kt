package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.content.Context
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
 * 主动消息的执行者（WorkManager）。
 *
 * 这里**直接跑生成逻辑**，不再去启动前台服务。
 *
 * 原来的实现是「Worker 起一个前台服务，由服务执行生成」，那条路在
 * targetSdk 31+ 上会塌：
 *
 *   1. 后台启动前台服务受限制。闹钟回调本是豁免场景，但 Android 14 起
 *      SCHEDULE_EXACT_ALARM 默认被拒，代码降级成不精确闹钟后就失去豁免，
 *      startForegroundService 抛 ForegroundServiceStartNotAllowedException；
 *   2. 那个异常被调用方的 catch (Exception) 吞掉，只剩一行 Log，UI 上
 *      毫无迹象——用户看到的就是「定时到了但没有任何反应」；
 *   3. Worker 自己也走同一条 startForegroundService，所以「兜底通道」
 *      其实是同一个失败点的复制品，起不到兜底作用。
 *
 * WorkManager 的 doWork 有约 10 分钟的执行窗口，足够跑完一次生成，
 * 且不受「后台启动前台服务」限制。项目里健康周期提醒（PeriodReminderWorker）
 * 就是这么做的，主动消息对齐它。
 */
class ProactiveMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProactiveMessageWorker"
        private const val UNIQUE_WORK_NAME = "proactive_message_work"
        private const val ONESHOT_WORK_NAME = "proactive_message_oneshot"

        /**
         * 排下一次延迟触发（唯一排程入口）。
         *
         * 刻意**不写**下次触发时间这个展示值——那是
         * [ProactiveMessageService.scheduleNext] 的职责。两条通道各写各的
         * 会让界面显示的时间与实际触发对不上。
         */
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

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes")
        }

        /**
         * 立刻跑一次（用户在设置页点开开关时用）。
         *
         * 用独立的 work name，避免把已排好的下一次延迟任务顶掉。
         */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProactiveMessageWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "Dispatched immediate proactive work")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONESHOT_WORK_NAME)
            Log.d(TAG, "Cancelled proactive message work")
        }

        /** Android 12+ 精确闹钟权限是否已授予。 */
        fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }

        /** 是否已忽略电池优化。 */
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

        // 唤醒锁：Doze 下 CPU 可能已经睡下去，生成要联网、要解密会话，
        // 不持锁容易被中途冻住。上限与 WorkManager 的执行窗口一致。
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProactiveMessage::WorkerWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L)

        return try {
            // 直接执行生成，不经过前台服务。
            val runner = ProactiveMessageRunner(applicationContext)
            val ran = runner.run()
            Log.d(TAG, "Proactive message run finished, reachedGeneration=$ran")
            Result.success()
        } catch (e: Exception) {
            // 生成本身抛异常（网络、API 报错等）。不要在这里重排——
            // ProactiveMessageRunner 的 finally 已经用 NonCancellable
            // 排过下一次了。这里再排会和它抢同一个 work name。
            Log.e(TAG, "Proactive message run failed", e)
            Result.failure()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
