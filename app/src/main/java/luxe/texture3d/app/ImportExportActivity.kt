package luxe.texture3d.app

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

class ImportExportActivity:AppCompatActivity(){
    private data class Picked(val uri:Uri,val name:String,val size:Long,val ext:String)
    private val dao by lazy{ImportDatabase.get(this).jobs()}
    private lateinit var queue:LinearLayout
    private lateinit var subtitle:TextView
    private var jobs:List<ImportJob> = emptyList()

    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->enqueueSelection(uris)}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);setContentView(buildUi());observeJobs();ImportQueueScheduler.ensureRunning(this)}

    private fun buildUi():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(0xff121212.toInt())}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(button("‹  Back"){finish()},LinearLayout.LayoutParams(dp(90),dp(38)));top.addView(text("IMPORT / EXPORT",17f,0xffeeeeee.toInt()).apply{setTypeface(typeface,1);setPadding(dp(14),0,0,0)},LinearLayout.LayoutParams(0,dp(42),1f));top.addView(button("Import Files"){picker.launch(arrayOf("*/*"))},LinearLayout.LayoutParams(dp(120),dp(38)));root.addView(top)
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,dp(6))}
        listOf("Queue","Recent Imports","Recent Conversions","Recent Exports","Failed").forEachIndexed{i,title->tabs.addView(text(title,12f,if(i==0)0xff8bc8ff.toInt() else 0xff888888.toInt()).apply{gravity=Gravity.CENTER;setBackgroundColor(if(i==0)0xff1e3a5f.toInt() else 0xff1b1b1b.toInt());if(i>0)setOnClickListener{showHistory(title)}},LinearLayout.LayoutParams(0,dp(36),1f).apply{rightMargin=dp(3)})};root.addView(tabs)
        subtitle=text("Persistent queue • One conversion at a time • Smallest first",11f,0xff777777.toInt()).apply{setPadding(dp(4),dp(6),0,dp(6))};root.addView(subtitle,LinearLayout.LayoutParams(-1,dp(34)))
        queue=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(queue)},LinearLayout.LayoutParams(-1,0,1f));return root
    }

    private fun observeJobs(){lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){dao.observeAll().collect{list->jobs=list;renderQueue(list)}}}}
    private fun renderQueue(all:List<ImportJob>){
        val active=all.filter{it.state=="WAITING"||it.state=="RUNNING"};queue.removeAllViews()
        if(active.isEmpty())queue.addView(text("Queue is empty. Completed work is stored in history.",13f,0xff777777.toInt()).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(100)))
        active.forEach{job->queue.addView(jobRow(job),LinearLayout.LayoutParams(-1,dp(82)).apply{bottomMargin=dp(5)})}
        subtitle.text="${active.count{it.state=="RUNNING"}} running • ${active.count{it.state=="WAITING"}} waiting • Queue survives app restarts"
    }
    private fun jobRow(job:ImportJob)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(7),dp(12),dp(7));setBackgroundResource(R.drawable.hub_project_row)
        val line=LinearLayout(this@ImportExportActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        line.addView(text("${job.displayName}   ${format(job.sourceSize)}",13f,0xffdddddd.toInt()),LinearLayout.LayoutParams(0,dp(27),1f));line.addView(text("Cancel",10f,0xffd58b7e.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{cancel(job)}},LinearLayout.LayoutParams(dp(70),dp(27)));addView(line)
        addView(text(job.message,11f,0xff8fa0b8.toInt()),LinearLayout.LayoutParams(-1,dp(24)));addView(ProgressBar(this@ImportExportActivity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=job.progress;progressTintList=android.content.res.ColorStateList.valueOf(0xff3b82f6.toInt())},LinearLayout.LayoutParams(-1,dp(5)))
    }

    private fun enqueueSelection(uris:List<Uri>){if(uris.isEmpty())return;val picked=uris.map(::inspect);picked.forEach{runCatching{contentResolver.takePersistableUriPermission(it.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}};val resourceExt=setOf("png","jpg","jpeg","tga","bmp","webp","dds","mtl","bin");val resources=picked.filter{it.ext in resourceExt};val models=(picked-resources.toSet()).sortedBy{it.size.takeIf{s->s>=0}?:Long.MAX_VALUE};if(models.isEmpty()){Toast.makeText(this,"No model or ZIP job selected",Toast.LENGTH_SHORT).show();return}
        lifecycleScope.launch(Dispatchers.IO){
            val batch=UUID.randomUUID().toString();val resourceJson=JSONArray(resources.map{it.uri.toString()}).toString()
            val entities=models.map{p->ImportJob(UUID.randomUUID().toString(),batch,p.uri.toString(),p.name,p.size,p.ext,resourceJson)}
            dao.putAll(entities)
            val drain=OneTimeWorkRequestBuilder<AssimpImportWorker>().addTag(AssimpImportWorker.UNIQUE_QUEUE).build()
            WorkManager.getInstance(this@ImportExportActivity).enqueueUniqueWork(AssimpImportWorker.UNIQUE_QUEUE,ExistingWorkPolicy.APPEND_OR_REPLACE,drain)
        }
    }
    private fun cancel(job:ImportJob){if(job.state=="RUNNING")runCatching{AssimpBridge().nativeCancel()};lifecycleScope.launch(Dispatchers.IO){dao.update(job.id,"CANCELLED",job.progress,"Cancelled")}}

    private fun showHistory(section:String){val selected=when(section){"Failed"->jobs.filter{it.state=="FAILED"};"Recent Exports"->emptyList();"Recent Conversions"->jobs.filter{it.state in setOf("COMPLETED","PARTIAL","DUPLICATE")};else->jobs.filter{it.state !in setOf("WAITING","RUNNING")}};val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));setBackgroundResource(R.drawable.hub_dialog_bg)};val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};head.addView(text(section,16f,Color.WHITE),LinearLayout.LayoutParams(0,dp(38),1f));head.addView(button("Close"){dialog.dismiss()},LinearLayout.LayoutParams(dp(80),dp(34)));panel.addView(head);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};if(selected.isEmpty())list.addView(text("No history yet",13f,0xff777777.toInt()).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(90)))else selected.take(100).forEach{job->list.addView(text(historySummary(job),11f,0xffb7c0cf.toInt()).apply{setPadding(dp(10),dp(7),dp(10),dp(7));setBackgroundResource(R.drawable.hub_project_row)},LinearLayout.LayoutParams(-1,dp(74)).apply{bottomMargin=dp(4)})};panel.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));dialog.setContentView(panel);dialog.show();dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply{dimAmount=.76f};setLayout((resources.displayMetrics.widthPixels*.78f).toInt(),(resources.displayMetrics.heightPixels*.78f).toInt())}}
    private fun historySummary(j:ImportJob)="${j.displayName}   •   ${j.state}\n${format(j.sourceSize)}   •   ${j.sourceFormat.uppercase()}${if(j.error.isNotBlank())"\n${j.error}" else ""}"
    private fun inspect(uri:Uri):Picked{var name=uri.lastPathSegment?:"model";var size=-1L;contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()){if(!it.isNull(0))name=it.getString(0);if(!it.isNull(1))size=it.getLong(1)}};return Picked(uri,name,size,name.substringAfterLast('.',"").lowercase())}
    private fun format(bytes:Long)=if(bytes<0)"" else if(bytes>1048576)"%.1f MB".format(bytes/1048576f) else "%.0f KB".format(bytes/1024f)
    private fun text(value:String,size:Float,color:Int)=TextView(this).apply{text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    private fun button(value:String,click:()->Unit)=text(value,12f,0xffeeeeee.toInt()).apply{gravity=Gravity.CENTER;setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{click()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
