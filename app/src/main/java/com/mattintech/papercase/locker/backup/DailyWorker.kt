package com.mattintech.papercase.locker.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mattintech.papercase.locker.reminders.ReminderNotifier
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Once a day: send due expiry reminders and run the automatic backup if one is due. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { ReminderNotifier.checkAndNotify(applicationContext) }.onFailure { it.printStackTrace() }
        runCatching { AutoBackupManager.runBackup(applicationContext) }.onFailure { it.printStackTrace() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "papercase-daily"
        private const val CATCH_UP_NAME = "papercase-catch-up"
        private val RUN_AT: LocalTime = LocalTime.of(9, 0)

        /** Safe to call on every app start; an existing schedule is kept. */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(RUN_AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)

            // Also run once now, so anything that came due while the app wasn't running
            // (or before the first scheduled run, e.g. right after an update) isn't held back a day
            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DailyWorker>().build()
            )
        }
    }
}
