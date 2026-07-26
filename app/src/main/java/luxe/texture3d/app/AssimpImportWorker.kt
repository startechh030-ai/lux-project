package luxe.texture3d.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One durable worker drains every WAITING/RUNNING job in size order. */
class AssimpImportWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    private val dao=ImportDatabase.get(context).jobs()
    private val notifications=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val largeIcon by lazy{BitmapFactory.decodeResource(applicationContext.resources,R.drawable.luxe_launcher)}

    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        createChannel();var completed=0
        while(!isStopped){
            val job=dao.nextPending()?:break
            try{
                setForeground(foreground(job.displayName,0,"Preparing import"))
                dao.update(job.id,"RUNNING",1,"Preparing import")
                val processor=PersistentImportProcessor(applicationContext)
                val result=processor.run(job){progress,message->
                    if(isStopped)runCatching{AssimpBridge().nativeCancel()}
                    kotlinx.coroutines.runBlocking{dao.update(job.id,"RUNNING",progress,message)}
                    setProgressAsync(workDataOf("jobId" to job.id,"progress" to progress,"message" to message))
                    setForegroundAsync(foreground(job.displayName,progress,message))
                }
                if(dao.get(job.id)?.state=="CANCELLED")continue
                val assets=JSONArray(result.assets).toString()
                dao.update(job.id,result.state,100,if(result.state=="FAILED")"Failed" else "Completed",result.error,assets)
                writeHistory(job,result)
                if(result.state!="FAILED"){completed++;notifyFileComplete(job.displayName,result.assets.size)}
            }catch(t:Throwable){
                dao.update(job.id,"FAILED",100,"Failed",t.message?:"Worker failure")
                writeHistory(job,PersistentImportProcessor.Result("FAILED",emptyList(),t.message?:"Worker failure"))
            }finally{
                runCatching{AssimpBridge().nativeCancel()};System.gc()
                val memoryClass=(applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
                delay(if(memoryClass<=256)1500 else 350)
            }
        }
        if(completed>0)notifyQueueComplete(completed)
        Result.success(workDataOf("completed" to completed))
    }

    private fun writeHistory(job:ImportJob,result:PersistentImportProcessor.Result){
        val json=JSONObject().put("jobId",job.id).put("sourceName",job.displayName).put("sourceFormat",job.sourceFormat).put("sourceBytes",job.sourceSize).put("status",result.state.lowercase()).put("error",result.error).put("assetIds",JSONArray(result.assets)).put("completedAt",System.currentTimeMillis())
        runCatching{File(AppFileSystem(applicationContext).importHistory,"${job.id}.json").writeText(json.toString())}
    }
    private fun createChannel(){notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Luxe Imports",NotificationManager.IMPORTANCE_LOW).apply{description="Background model conversion progress"})}
    private fun contentIntent():PendingIntent=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,ImportExportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun baseBuilder()=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(R.drawable.ic_notification).setLargeIcon(largeIcon).setContentIntent(contentIntent()).setOnlyAlertOnce(true)
    private fun foreground(name:String,progress:Int,message:String)=ForegroundInfo(FOREGROUND_ID,baseBuilder().setContentTitle("Importing $name").setContentText(message).setOngoing(true).setProgress(100,progress,progress<=1).build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    private fun notifyFileComplete(name:String,assets:Int){notifications.notify(name.hashCode(),baseBuilder().setContentTitle("✓ Your file has been imported successfully").setContentText("$name • $assets asset(s) ready").setOngoing(false).setProgress(0,0,false).setAutoCancel(true).build())}
    private fun notifyQueueComplete(count:Int){notifications.notify(COMPLETE_ID,baseBuilder().setContentTitle("✓ All imports completed").setContentText("$count file(s) imported successfully").setOngoing(false).setProgress(0,0,false).setAutoCancel(true).build())}

    companion object{const val UNIQUE_QUEUE="luxe-assimp-imports";private const val CHANNEL="luxe_imports";private const val FOREGROUND_ID=5101;private const val COMPLETE_ID=5102}
}
