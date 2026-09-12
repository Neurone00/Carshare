package io.github.neurone00.adblock.vpn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.update.Updater
import java.util.concurrent.TimeUnit

/** Refreshes blocklists and checks for app updates in the background once a day. */
class ListUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var ok = true
        if (Prefs.autoUpdate) {
            try {
                ListRepository.update(applicationContext, force = true)
            } catch (_: Exception) {
                ok = false
            }
        }
        if (Prefs.autoUpdateApp) {
            try {
                val info = Updater.check(applicationContext, manual = false)
                if (info != null) Updater.downloadAndInstall(applicationContext, info)
            } catch (_: Exception) {
                ok = false
            }
        }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "blocklist-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
