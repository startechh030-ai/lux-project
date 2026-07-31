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
        val stage=File(files.staging,job.id);val created=mutableListOf<String>();val errors=mutableListOf<String>();var newAssets=0;var duplicates=0
        try{
            stage.deleteRecursively();stage.mkdirs();progress(5,"Staging source")
            val source=File(stage,job.displayName);copyUri(Uri.parse(job.sourceUri),source)
            JSONArray(job.resourceUrisJson).let{array->for(i in 0 until array.length()){val uri=Uri.parse(array.getString(i));copyUri(uri,File(stage,displayName(uri)))}}
            val imageExtensions=setOf("png","jpg","jpeg","tga","bmp","webp","dds","svg","gif","heic","heif","avif")
            if(job.sourceFormat in imageExtensions){
                progress(45,"Creating Texture Element")
                val resourceUid=AssetFamilyBuilder(context).importStandaloneImage(source,job.displayName)
                return Result("COMPLETED",listOf(resourceUid))
            }
            val candidates=if(job.sourceFormat=="zip"||job.sourceFormat=="zae"){ 
                progress(15,"Exploring nested ZIP package")
                SafeZipExtractor.extractRecursive(source,File(stage,"unpacked")).files.filter{it.isFile&&it.length()>0&&it.extension.lowercase() in supported}.distinctBy{it.canonicalPath}
            }else listOf(source)
            if(candidates.isEmpty())error("No supported model found")
            candidates.forEachIndexed{index,candidate->
                progress(20+(index*60/candidates.size),"Converting ${index+1}/${candidates.size}: ${candidate.name}")
                val assetId="${safe(candidate.nameWithoutExtension)}-${System.currentTimeMillis()}-$index"
                val transaction=File(files.assets,".converting-$assetId");val finalOutput=File(files.assets,assetId)
                transaction.deleteRecursively();transaction.mkdirs()
                runCatching{
                    copyResources(candidate.parentFile?:stage,transaction)
                    val nativeMetadata:JSONObject
                    if(candidate.extension.equals("gltf",true)){
                        // Already-canonical glTF must not depend on Assimp reader registration.
                        // Preserve JSON and companion resources, then run Luxe repair/validation.
                        GltfSourceNormalizer.normalize(candidate,File(transaction,"model.gltf"))
                        nativeMetadata=JSONObject().put("profile","preserve_gltf_direct").put("sourceExtension","gltf").put("sourceUpAxis","unknown").put("unitScaleFactor",1.0).put("animationCount",0).put("cameraCount",0).put("lightCount",0).put("materialCount",0).put("hasBones",false).put("warnings",JSONArray())
                    }else{
                        val nativeError=bridge.nativeConvertToGltf(candidate.absolutePath,transaction.absolutePath);if(nativeError.isNotEmpty())error(nativeError)
                        val nativeMetadataFile=File(transaction,"conversion_native.json")
                        nativeMetadata=runCatching{JSONObject(nativeMetadataFile.readText())}.getOrElse{JSONObject()};nativeMetadataFile.delete()
                    }
                    progress(78+(index*7/candidates.size),"Collecting textures for ${candidate.name}")
                    val textureReport=GltfTexturePipeline.process(transaction,stage)
                    progress(86+(index*6/candidates.size),"Validating ${candidate.name}")
                    val report=GltfValidator.validate(transaction)
                    if(!report.valid)error("glTF validation failed: ${report.errors.joinToString("; ")}")
                    val details=GltfMetadataExtractor.extract(transaction)
                    val contentHash=AssetFingerprint.contentHash(transaction)
                    val duplicate=AssetFingerprint.findDuplicate(files.assets,contentHash)
                    if(duplicate!=null){transaction.deleteRecursively();created+=duplicate.name;duplicates++;return@runCatching}
                    createThumbnail(candidate.parentFile?:stage,File(transaction,"thumbnail.png"))
                    val nativeWarnings=nativeMetadata.optJSONArray("warnings")?.let{array->List(array.length()){array.optString(it)}}?:emptyList()
                    val allWarnings=(textureReport.warnings+report.warnings+details.warnings+nativeWarnings).distinct()
                    val inventory=JSONArray();AssetFingerprint.inventory(transaction).forEach{inventory.put(JSONObject().put("path",it.path).put("size",it.size).put("sha256",it.sha256))}
                    val bounds=if(details.boundsMin!=null&&details.boundsMax!=null)JSONObject().put("min",GltfMetadataExtractor.vectorJson(details.boundsMin)).put("max",GltfMetadataExtractor.vectorJson(details.boundsMax)) else JSONObject.NULL
                    val metadata=JSONObject().put("id",assetId).put("displayName",candidate.name).put("sourceFormat",candidate.extension.lowercase()).put("conversionProfile",nativeMetadata.optString("profile","preserve")).put("sourceUpAxis",nativeMetadata.optString("sourceUpAxis","unknown")).put("sourceUnitScale",nativeMetadata.optDouble("unitScaleFactor",1.0)).put("convertedUpAxis","source-preserved").put("convertedUnits","source-preserved").put("hasBones",nativeMetadata.optBoolean("hasBones",false)).put("assetKind",if(nativeMetadata.optBoolean("hasBones",false)||details.animationCount>0)"animated" else "static").put("cameraCount",nativeMetadata.optInt("cameraCount",0)).put("lightCount",nativeMetadata.optInt("lightCount",0)).put("sourceHash",AssetFingerprint.fileHash(candidate)).put("contentHash",contentHash).put("outputFormat","gltf2").put("model","model.gltf").put("thumbnail","thumbnail.png").put("status",if(allWarnings.isEmpty())"ready" else "ready_with_warnings").put("meshCount",details.meshCount).put("nodeCount",details.nodeCount).put("materialCount",details.materialCount).put("texturedMaterialCount",details.texturedMaterialCount).put("textureCount",textureReport.textureFiles).put("textureFiles",JSONArray(textureReport.files)).put("animationCount",details.animationCount).put("vertexCount",details.vertexCount).put("triangleCount",details.triangleCount).put("bounds",bounds).put("files",inventory).put("warnings",JSONArray(allWarnings)).put("createdAt",System.currentTimeMillis())
                    File(transaction,"asset.json").writeText(metadata.toString())
                    check(!finalOutput.exists()){ "Asset destination already exists" }
                    check(transaction.renameTo(finalOutput)){ "Unable to finalize converted asset" }
                    AssetFamilyBuilder(context).build(finalOutput)
                    created+=assetId;newAssets++
                }.onFailure{transaction.deleteRecursively();finalOutput.takeIf{it.exists()&&!File(it,"asset.json").exists()}?.deleteRecursively();errors+="${candidate.name}: ${it.message}"}
            }
            if(created.isEmpty())return Result("FAILED",emptyList(),errors.joinToString(" | ").ifBlank{"Conversion failed"})
            val state=when{errors.isNotEmpty()->"PARTIAL";newAssets==0&&duplicates>0->"DUPLICATE";else->"COMPLETED"}
            return Result(state,created,errors.joinToString(" | "))
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
