package luxe.texture3d.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

class PersistentImportProcessor(private val context:Context){
    data class Result(val state:String,val assets:List<String>,val error:String="")
    private val files=AppFileSystem(context)
    private val bridge=AssimpBridge()
    private val supported=setOf("fbx","dae","obj","stl","ply","3ds","dxf","glb","gltf","blend")
    private val resources=setOf("png","jpg","jpeg","tga","bmp","webp","dds","mtl","bin")

    fun run(job:ImportJob,progress:(Int,String)->Unit):Result{
        val stage=File(files.staging,job.id);val created=mutableListOf<String>();val errors=mutableListOf<String>()
        try{
            stage.deleteRecursively();stage.mkdirs();progress(5,"Staging source")
            val source=File(stage,job.displayName);copyUri(Uri.parse(job.sourceUri),source)
            JSONArray(job.resourceUrisJson).let{array->for(i in 0 until array.length()){val uri=Uri.parse(array.getString(i));copyUri(uri,File(stage,displayName(uri)))}}
            val candidates=if(job.sourceFormat=="zip"){
                progress(15,"Exploring nested ZIP package")
                SafeZipExtractor.extractRecursive(source,File(stage,"unpacked")).files.filter{it.extension.lowercase() in supported}.distinctBy{it.canonicalPath}
            }else listOf(source)
            if(candidates.isEmpty())error("No supported model found")
            candidates.forEachIndexed{index,candidate->
                progress(20+(index*60/candidates.size),"Converting ${index+1}/${candidates.size}: ${candidate.name}")
                val assetId="${safe(candidate.nameWithoutExtension)}-${System.currentTimeMillis()}-$index"
                val transaction=File(files.assets,".converting-$assetId");val finalOutput=File(files.assets,assetId)
                transaction.deleteRecursively();transaction.mkdirs()
                runCatching{
                    copyResources(candidate.parentFile?:stage,transaction)
                    val nativeError=bridge.nativeConvertToGltf(candidate.absolutePath,transaction.absolutePath);if(nativeError.isNotEmpty())error(nativeError)
                    progress(78+(index*7/candidates.size),"Collecting textures for ${candidate.name}")
                    val textureReport=GltfTexturePipeline.process(transaction,stage)
                    progress(86+(index*6/candidates.size),"Validating ${candidate.name}")
                    val report=GltfValidator.validate(transaction)
                    if(!report.valid)error("glTF validation failed: ${report.errors.joinToString("; ")}")
                    createThumbnail(candidate.parentFile?:stage,File(transaction,"thumbnail.png"))
                    val allWarnings=(textureReport.warnings+report.warnings).distinct()
                    val metadata=JSONObject().put("id",assetId).put("displayName",candidate.name).put("sourceFormat",candidate.extension.lowercase()).put("outputFormat","gltf2").put("model","model.gltf").put("thumbnail","thumbnail.png").put("status",if(allWarnings.isEmpty())"ready" else "ready_with_warnings").put("meshCount",report.meshCount).put("nodeCount",report.nodeCount).put("materialCount",report.materialCount).put("textureCount",textureReport.textureFiles).put("textureFiles",JSONArray(textureReport.files)).put("animationCount",report.animationCount).put("warnings",JSONArray(allWarnings)).put("createdAt",System.currentTimeMillis())
                    File(transaction,"asset.json").writeText(metadata.toString())
                    check(!finalOutput.exists()){ "Asset destination already exists" }
                    check(transaction.renameTo(finalOutput)){ "Unable to finalize converted asset" }
                    created+=assetId
                }.onFailure{transaction.deleteRecursively();finalOutput.takeIf{it.exists()&&!File(it,"asset.json").exists()}?.deleteRecursively();errors+="${candidate.name}: ${it.message}"}
            }
            if(created.isEmpty())return Result("FAILED",emptyList(),errors.joinToString(" | ").ifBlank{"Conversion failed"})
            return Result(if(errors.isEmpty())"COMPLETED" else "PARTIAL",created,errors.joinToString(" | "))
        }catch(t:Throwable){return Result("FAILED",created,t.message?:"Import failed")}
        finally{stage.deleteRecursively();runCatching{bridge.nativeCancel()}}
    }

    private fun copyUri(uri:Uri,out:File){out.parentFile?.mkdirs();context.contentResolver.openInputStream(uri)?.use{input->FileOutputStream(out).use{input.copyTo(it,256*1024)}}?:error("Unable to read $uri")}
    private fun displayName(uri:Uri):String{var n=uri.lastPathSegment?:"resource";context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))n=it.getString(0)};return n}
    private fun copyResources(root:File,out:File){root.walkTopDown().filter{it.isFile&&it.extension.lowercase() in resources}.forEach{source->val target=File(out,runCatching{source.relativeTo(root).path}.getOrDefault(source.name));target.parentFile?.mkdirs();source.copyTo(target,true)}}
    private fun createThumbnail(root:File,out:File){val image=root.walkTopDown().firstOrNull{it.isFile&&it.extension.lowercase() in setOf("png","jpg","jpeg","webp","bmp")};val bitmap=image?.let{sampled(it,512)}?:BitmapFactory.decodeResource(context.resources,R.drawable.luxe_launcher);val scale=minOf(1f,512f/maxOf(bitmap.width,bitmap.height));val thumb=if(scale<1)Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt(),(bitmap.height*scale).toInt(),true)else bitmap;FileOutputStream(out).use{thumb.compress(Bitmap.CompressFormat.PNG,100,it)};if(thumb!==bitmap)thumb.recycle();bitmap.recycle()}
    private fun sampled(file:File,max:Int):Bitmap?{val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,b);var s=1;while(b.outWidth/s>max*2||b.outHeight/s>max*2)s*=2;return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=s})}
    private fun safe(v:String)=v.replace(Regex("[^A-Za-z0-9._-]"),"_").take(48).ifBlank{"asset"}
}
