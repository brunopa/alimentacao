package com.bruno.alimentacao

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Envia a foto em segundo plano, com reenvio automático quando houver internet.
 * Se o envio falhar (sem rede, servidor fora), o WorkManager reagenda com backoff.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val uriStr = inputData.getString(KEY_URI) ?: return Result.success()
        val fileName = inputData.getString(KEY_FILENAME) ?: "foto.jpg"
        val takenAt = inputData.getLong(KEY_TAKEN_AT, System.currentTimeMillis())

        val res = Uploader.uploadFromUriSync(applicationContext, Uri.parse(uriStr), takenAt, fileName)
        return if (res.ok) Result.success() else Result.retry()
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_FILENAME = "filename"
        private const val KEY_TAKEN_AT = "taken_at"

        /** Agenda o envio de uma foto recém-salva. */
        fun enqueue(context: Context, photoUri: Uri, fileName: String, takenAt: Long) {
            val data = Data.Builder()
                .putString(KEY_URI, photoUri.toString())
                .putString(KEY_FILENAME, fileName)
                .putLong(KEY_TAKEN_AT, takenAt)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
