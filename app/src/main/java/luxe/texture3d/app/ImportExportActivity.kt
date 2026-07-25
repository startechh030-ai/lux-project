package luxe.texture3d.app

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
        listOf("Queue","Recent Imports","Recent Conversions","Recent Exports","Failed").forEachIndexed{i,t->tabs.addView(TextView(this@ImportExportActivity).apply{text=t;textSize=12f;gravity=Gravity.CENTER;setTextColor(if(i==0)0xff8bc8ff.toInt() else 0xff888888.toInt());setBackgroundColor(if(i==0)0xff1e3a5f.toInt() else 0xff1b1b1b.toInt());if(i>0)setOnClickListener{showHistory(t)}},LinearLayout.LayoutParams(0,dp(36),1f).apply{rightMargin=dp(3)})}
        root.addView(tabs)
        subtitle=TextView(this).apply{text="Assimp ${runCatching{bridge.nativeVersion()}.getOrDefault("unavailable")} • One conversion at a time • Smallest first";textSize=11f;setTextColor(0xff777777.toInt());setPadding(dp(4),dp(6),0,dp(6))}
        root.addView(subtitle,LinearLayout.LayoutParams(-1,dp(34)))
        queue=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(queue)},LinearLayout.LayoutParams(-1,0,1f))
        return root
    }

    private fun showHistory(section:String){
        val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));setBackgroundResource(R.drawable.hub_dialog_bg)}
        val heading=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heading.addView(TextView(this).apply{text=section;textSize=16f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,dp(38),1f))
        heading.addView(button("Close"){dialog.dismiss()},LinearLayout.LayoutParams(dp(80),dp(34)));panel.addView(heading)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val history=files.importHistory.listFiles()?.sortedByDescending{it.lastModified()}?:emptyList()
        val selected=when(section){"Failed"->history.filter{runCatching{it.readText().contains("\\\"status\\\":\\\"failed\\\"")}.getOrDefault(false)};"Recent Exports"->emptyList();else->history}
        if(selected.isEmpty())list.addView(TextView(this).apply{text="No history yet";textSize=13f;gravity=Gravity.CENTER;setTextColor(0xff777777.toInt())},LinearLayout.LayoutParams(-1,dp(90)))
        else selected.take(100).forEach{file->list.addView(TextView(this).apply{text=runCatching{file.readText()}.getOrDefault(file.name);textSize=10f;setTextColor(0xffb7c0cf.toInt());setPadding(dp(8),dp(6),dp(8),dp(6));setBackgroundResource(R.drawable.hub_project_row)},LinearLayout.LayoutParams(-1,dp(70)).apply{bottomMargin=dp(4)})}
        panel.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));dialog.setContentView(panel);dialog.show()
        dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply{dimAmount=.76f};setLayout((resources.displayMetrics.widthPixels*.78f).toInt(),(resources.displayMetrics.heightPixels*.78f).toInt())}
    }

    private fun inspect(uri:Uri):Item{
        var name=uri.lastPathSegment?:"model";var size=-1L
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){if(!c.isNull(0))name=c.getString(0);if(!c.isNull(1))size=c.getLong(1)}}
        return Item(uri,name,size)
    }

    private fun runQueue(items:List<Item>){
        val resourceExt=setOf("png","jpg","jpeg","tga","bmp","webp","dds","mtl","bin")
        val resources=items.filter{it.name.substringAfterLast('.',"").lowercase() in resourceExt}
        val jobs=items-resources.toSet()
        queue.removeAllViews();val rows=jobs.associateWith{addRow(it)}
        if(jobs.isEmpty()){subtitle.text="No supported model job selected • ${resources.size} resource file(s) ignored";return}
        worker.execute{
            jobs.forEachIndexed{index,item->
                if(Thread.currentThread().isInterrupted)return@execute
                val row=rows.getValue(item);status(row,"Staging",8)
                convertOne(item,row,index+1,jobs.size,resources)
            }
            runOnUiThread{subtitle.text="Queue complete • Assets: ${files.assets.absolutePath}"}
        }
    }

    private fun convertOne(item:Item,row:ProgressRow,position:Int,total:Int,selectedResources:List<Item>){
        val started=System.currentTimeMillis();val memoryBefore=usedMemory();val ext=item.name.substringAfterLast('.',"").lowercase()
        val supported=setOf("fbx","dae","obj","stl","ply","3ds","dxf","glb","gltf","blend")
        val job=File(files.staging,UUID.randomUUID().toString());val created=mutableListOf<String>();var textureCount=0;var outputBytes=0L
        try{
            job.mkdirs();val source=File(job,item.name)
            status(row,"Staging",8)
            contentResolver.openInputStream(item.uri)?.use{input->FileOutputStream(source).use{input.copyTo(it,256*1024)}}?:error("Unable to read source")
            selectedResources.forEach{resource->contentResolver.openInputStream(resource.uri)?.use{input->FileOutputStream(File(job,resource.name)).use{input.copyTo(it,128*1024)}}}
            val candidates:List<File>
            if(ext=="zip"){
                status(row,"Exploring nested ZIP package",18)
                val extracted=SafeZipExtractor.extractRecursive(source,File(job,"unpacked"))
                candidates=extracted.files.filter{it.extension.lowercase() in supported}.distinctBy{it.canonicalPath}
                if(candidates.isEmpty())error("No supported 3D model was found in the ZIP package")
            }else{
                if(ext !in supported)error("Unsupported .$ext")
                candidates=listOf(source)
            }
            candidates.forEachIndexed{candidateIndex,candidate->
                status(row,"Converting ${candidateIndex+1}/${candidates.size} • Queue $position/$total",25+(candidateIndex*55/candidates.size))
                val output=File(files.assets,"${safeName(candidate.nameWithoutExtension)}-${System.currentTimeMillis()}-$candidateIndex");output.mkdirs()
                copyResources(candidate.parentFile?:job,output).also{textureCount+=it}
                val nativeError=bridge.nativeConvertToGltf(candidate.absolutePath,output.absolutePath)
                if(nativeError.isNotEmpty()){output.deleteRecursively();throw IllegalStateException(nativeError)}
                createBestThumbnail(candidate.parentFile?:job,File(output,"thumbnail.png"))
                File(output,"asset.json").writeText("{\"id\":\"${output.name}\",\"displayName\":\"${candidate.name.replace("\"","_")}\",\"sourceFormat\":\"${candidate.extension.lowercase()}\",\"outputFormat\":\"gltf2\",\"model\":\"model.gltf\",\"thumbnail\":\"thumbnail.png\",\"status\":\"ready\"}")
                outputBytes+=output.walkTopDown().filter{it.isFile}.sumOf{it.length()};created+=output.name
            }
            val duration=System.currentTimeMillis()-started;writeHistory(item,ext,created,textureCount,duration,memoryBefore,outputBytes,"completed",null)
            status(row,"Completed • ${created.size} asset(s)",100)
        }catch(t:Throwable){writeHistory(item,ext,created,textureCount,System.currentTimeMillis()-started,memoryBefore,outputBytes,"failed",t.message);status(row,t.message?:"Conversion failed",100,true)}
        finally{job.deleteRecursively();runCatching{bridge.nativeCancel()};System.gc()}
    }

    private fun copyResources(root:File,output:File):Int{
        val allowed=setOf("png","jpg","jpeg","tga","bmp","webp","dds","mtl","bin");var count=0
        root.walkTopDown().filter{it.isFile&&it.extension.lowercase() in allowed}.forEach{source->
            val relative=runCatching{source.relativeTo(root).path}.getOrDefault(source.name)
            val target=File(output,relative);target.parentFile?.mkdirs();runCatching{source.copyTo(target,true)}
            if(source.extension.lowercase() in allowed-setOf("mtl","bin"))count++
        };return count
    }

    private fun createBestThumbnail(searchRoot:File,out:File){
        val image=searchRoot.walkTopDown().firstOrNull{it.isFile&&it.extension.lowercase() in setOf("png","jpg","jpeg","webp","bmp")}
        val bitmap=image?.let{runCatching{BitmapFactory.decodeFile(it.absolutePath)}.getOrNull()}
        if(bitmap!=null){FileOutputStream(out).use{bitmap.compress(Bitmap.CompressFormat.PNG,88,it)};bitmap.recycle()}else createFallbackThumbnail(out)
    }

    private fun writeHistory(item:Item,format:String,assetIds:List<String>,textures:Int,duration:Long,memoryBefore:Long,outputBytes:Long,state:String,error:String?){
        val id="${System.currentTimeMillis()}-${UUID.randomUUID()}";val safeError=(error?:"").replace("\","/").replace("\"","'")
        File(files.importHistory,"$id.json").writeText("{\"sourceName\":\"${item.name.replace("\"","_")}\",\"sourceFormat\":\"$format\",\"detectedModels\":${assetIds.size},\"textureCount\":$textures,\"sourceBytes\":${item.size},\"outputBytes\":$outputBytes,\"durationMs\":$duration,\"memoryDeltaBytes\":${(usedMemory()-memoryBefore).coerceAtLeast(0)},\"status\":\"$state\",\"error\":\"$safeError\",\"assetIds\":[${assetIds.joinToString(","){"\"$it\""}}]}")
    }
    private fun usedMemory()=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()

    private data class ProgressRow(val status:TextView,val bar:ProgressBar)
    private fun addRow(item:Item):ProgressRow{
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundResource(R.drawable.hub_project_row)}
        val title=TextView(this).apply{text="${item.name}   ${format(item.size)}";textSize=13f;setTextColor(0xffdddddd.toInt())}
        val status=TextView(this).apply{text="Waiting";textSize=11f;setTextColor(0xff888888.toInt())}
        val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0;progressTintList=android.content.res.ColorStateList.valueOf(0xff3b82f6.toInt())}
        card.addView(title);card.addView(status);card.addView(bar,LinearLayout.LayoutParams(-1,dp(5)).apply{topMargin=dp(6)})
        queue.addView(card,LinearLayout.LayoutParams(-1,dp(74)).apply{bottomMargin=dp(5)});return ProgressRow(status,bar)
    }
    private fun status(row:ProgressRow,text:String,progress:Int,failed:Boolean=false)=runOnUiThread{row.status.text=text;row.status.setTextColor(if(failed)0xffff8a65.toInt() else 0xff8fa0b8.toInt());row.bar.progressTintList=android.content.res.ColorStateList.valueOf(if(failed)0xffd85b4a.toInt() else if(progress>=100)0xff3aa66b.toInt() else 0xff3b82f6.toInt());row.bar.progress=progress}
    private fun createFallbackThumbnail(out:File){val bitmap=BitmapFactory.decodeResource(resources,R.drawable.luxe_launcher);FileOutputStream(out).use{bitmap.compress(Bitmap.CompressFormat.PNG,90,it)};bitmap.recycle()}
    private fun safeName(v:String)=v.replace(Regex("[^A-Za-z0-9._-]"),"_").take(48).ifBlank{"asset"}
    private fun format(bytes:Long)=if(bytes<0)"" else if(bytes>1024*1024)"%.1f MB".format(bytes/1048576f) else "%.0f KB".format(bytes/1024f)
    private fun button(text:String,click:()->Unit)=TextView(this).apply{this.text=text;textSize=12f;gravity=Gravity.CENTER;setTextColor(0xffeeeeee.toInt());setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{click()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
