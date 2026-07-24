package luxe.texture3d.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class ImportExportActivity : AppCompatActivity() {
    private data class Item(val uri:Uri,val name:String,val size:Long)
    private val worker=Executors.newSingleThreadExecutor()
    private val bridge by lazy{AssimpBridge()}
    private val files by lazy{AppFileSystem(this)}
    private lateinit var queue:LinearLayout
    private lateinit var subtitle:TextView

    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val items=uris.map{inspect(it)}.sortedBy{it.size.takeIf{s->s>=0}?:Long.MAX_VALUE}
        if(items.isNotEmpty())runQueue(items)
    }

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(buildUi())}
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}

    private fun buildUi():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(0xff121212.toInt())}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(button("‹  Back"){finish()},LinearLayout.LayoutParams(dp(90),dp(38)))
        top.addView(TextView(this).apply{text="IMPORT / EXPORT";textSize=17f;setTextColor(0xffeeeeee.toInt());setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,0,0)},LinearLayout.LayoutParams(0,dp(42),1f))
        top.addView(button("Import Files"){picker.launch(arrayOf("*/*"))},LinearLayout.LayoutParams(dp(120),dp(38)))
        root.addView(top)
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,dp(6))}
        listOf("Queue","Recent Imports","Recent Conversions","Recent Exports","Failed").forEachIndexed{i,t->tabs.addView(TextView(this@ImportExportActivity).apply{text=t;textSize=12f;gravity=Gravity.CENTER;setTextColor(if(i==0)0xff8bc8ff.toInt() else 0xff888888.toInt());setBackgroundColor(if(i==0)0xff1e3a5f.toInt() else 0xff1b1b1b.toInt())},LinearLayout.LayoutParams(0,dp(36),1f).apply{rightMargin=dp(3)})}
        root.addView(tabs)
        subtitle=TextView(this).apply{text="Assimp ${runCatching{bridge.nativeVersion()}.getOrDefault("unavailable")} • One conversion at a time • Smallest first";textSize=11f;setTextColor(0xff777777.toInt());setPadding(dp(4),dp(6),0,dp(6))}
        root.addView(subtitle,LinearLayout.LayoutParams(-1,dp(34)))
        queue=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(queue)},LinearLayout.LayoutParams(-1,0,1f))
        return root
    }

    private fun inspect(uri:Uri):Item{
        var name=uri.lastPathSegment?:"model";var size=-1L
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){if(!c.isNull(0))name=c.getString(0);if(!c.isNull(1))size=c.getLong(1)}}
        return Item(uri,name,size)
    }

    private fun runQueue(items:List<Item>){
        queue.removeAllViews();val rows=items.associateWith{addRow(it)}
        worker.execute{
            items.forEachIndexed{index,item->
                if(Thread.currentThread().isInterrupted)return@execute
                val row=rows.getValue(item);status(row,"Staging",8)
                convertOne(item,row,index+1,items.size)
            }
            runOnUiThread{subtitle.text="Queue complete • Output: ${files.imports.absolutePath}"}
        }
    }

    private fun convertOne(item:Item,row:ProgressRow,position:Int,total:Int){
        val ext=item.name.substringAfterLast('.',"").lowercase()
        if(ext !in setOf("fbx","dae","obj","stl","ply","3ds","dxf","glb","gltf")){status(row,"Unsupported .$ext",100,true);return}
        val job=File(files.staging,UUID.randomUUID().toString());val output=File(files.imports,"${safeName(item.name.substringBeforeLast('.'))}-${System.currentTimeMillis()}")
        try{
            job.mkdirs();output.mkdirs();val source=File(job,item.name)
            contentResolver.openInputStream(item.uri)?.use{input->FileOutputStream(source).use{input.copyTo(it,256*1024)}}?:error("Unable to read source")
            status(row,"Converting $position/$total",35)
            val nativeError=bridge.nativeConvertToGltf(source.absolutePath,output.absolutePath)
            if(nativeError.isNotEmpty())throw IllegalStateException(nativeError)
            status(row,"Writing metadata",82)
            createFallbackThumbnail(File(output,"thumbnail.png"))
            File(output,"import.json").writeText("{\"id\":\"${output.name}\",\"displayName\":\"${item.name.replace("\"","_")}\",\"sourceFormat\":\"$ext\",\"outputFormat\":\"gltf2\",\"model\":\"model.gltf\",\"thumbnail\":\"thumbnail.png\",\"status\":\"ready\"}")
            status(row,"Completed",100)
        }catch(t:Throwable){output.deleteRecursively();status(row,t.message?:"Conversion failed",100,true)}finally{job.deleteRecursively();System.gc()}
    }

    private data class ProgressRow(val status:TextView,val bar:ProgressBar)
    private fun addRow(item:Item):ProgressRow{
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundResource(R.drawable.hub_project_row)}
        val title=TextView(this).apply{text="${item.name}   ${format(item.size)}";textSize=13f;setTextColor(0xffdddddd.toInt())}
        val status=TextView(this).apply{text="Waiting";textSize=11f;setTextColor(0xff888888.toInt())}
        val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0;progressTintList=android.content.res.ColorStateList.valueOf(0xff3b82f6.toInt())}
        card.addView(title);card.addView(status);card.addView(bar,LinearLayout.LayoutParams(-1,dp(5)).apply{topMargin=dp(6)})
        queue.addView(card,LinearLayout.LayoutParams(-1,dp(74)).apply{bottomMargin=dp(5)});return ProgressRow(status,bar)
    }
    private fun status(row:ProgressRow,text:String,progress:Int,failed:Boolean=false)=runOnUiThread{row.status.text=text;row.status.setTextColor(if(failed)0xffff8a65.toInt() else 0xff8fa0b8.toInt());row.bar.progress=progress}
    private fun createFallbackThumbnail(out:File){val bitmap=BitmapFactory.decodeResource(resources,R.drawable.luxe_launcher);FileOutputStream(out).use{bitmap.compress(Bitmap.CompressFormat.PNG,90,it)};bitmap.recycle()}
    private fun safeName(v:String)=v.replace(Regex("[^A-Za-z0-9._-]"),"_").take(48).ifBlank{"asset"}
    private fun format(bytes:Long)=if(bytes<0)"" else if(bytes>1024*1024)"%.1f MB".format(bytes/1048576f) else "%.0f KB".format(bytes/1024f)
    private fun button(text:String,click:()->Unit)=TextView(this).apply{this.text=text;textSize=12f;gravity=Gravity.CENTER;setTextColor(0xffeeeeee.toInt());setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{click()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
