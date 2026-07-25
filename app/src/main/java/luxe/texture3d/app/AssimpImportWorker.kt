package luxe.texture3d.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AssimpImportWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    private val dao=ImportDatabase.get(context).jobs()
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        val id=inputData.getString(KEY_JOB_ID)?:return@withContext Result.failure()
        val job=dao.get(id)?:return@withContext Result.failure()
        setForeground(foreground(job.displayName,0,"Preparing import"))
        dao.update(id,"RUNNING",1,"Preparing import")
        val processor=PersistentImportProcessor(applicationContext)
        val result=processor.run(job){progress,message->
            if(isStopped)AssimpBridge().nativeCancel()
            kotlinx.coroutines.runBlocking{dao.update(id,"RUNNING",progress,message)}
            setProgressAsync(workDataOf("progress" to progress,"message" to message))
        }
        val assets=JSONArray(result.assets).toString()
        dao.update(id,result.state,100,if(result.state=="FAILED")"Failed" else "Completed",result.error,assets)
        val history=org.json.JSONObject().put("jobId",id).put("sourceName",job.displayName).put("sourceFormat",job.sourceFormat).put("sourceBytes",job.sourceSize).put("status",result.state.lowercase()).put("error",result.error).put("assetIds",JSONArray(result.assets)).put("completedAt",System.currentTimeMillis())
        runCatching{java.io.File(AppFileSystem(applicationContext).importHistory,"$id.json").writeText(history.toString())}
        if(result.state=="FAILED")Result.failure(workDataOf("error" to result.error))else Result.success(workDataOf("assets" to assets))
    }
    override fun onStopped(){runCatching{AssimpBridge().nativeCancel()};super.onStopped()}

    private fun foreground(name:String,progress:Int,message:String):ForegroundInfo{
        val channel="luxe_imports";val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel,"Luxe Imports",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,channel).setSmallIcon(R.drawable.ic_luxe_logo).setContentTitle("Importing $name").setContentText(message).setOngoing(true).setProgress(100,progress,progress==0).build()
        return ForegroundInfo(name.hashCode(),notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object{const val KEY_JOB_ID="job_id";const val UNIQUE_QUEUE="luxe-assimp-imports"}
}
