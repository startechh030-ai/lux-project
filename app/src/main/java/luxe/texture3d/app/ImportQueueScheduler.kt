package luxe.texture3d.app

import android.content.Context
import androidx.work.*

object ImportQueueScheduler {
    fun ensureRunning(context:Context){
        val request=OneTimeWorkRequestBuilder<AssimpImportWorker>().addTag(AssimpImportWorker.UNIQUE_QUEUE).build()
        WorkManager.getInstance(context).enqueueUniqueWork(AssimpImportWorker.UNIQUE_QUEUE,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
}
