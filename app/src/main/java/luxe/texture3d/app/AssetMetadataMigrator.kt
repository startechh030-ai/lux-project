package luxe.texture3d.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Backfills Phase 2C metadata for assets converted by older app versions. */
object AssetMetadataMigrator {
    private val executor=Executors.newSingleThreadExecutor()
    fun migrateAsync(context:Context,onComplete:()->Unit={}){executor.execute{runCatching{migrate(context)};onComplete()}}

    fun migrate(context:Context):Int{
        val assets=AppFileSystem(context).assets;var changed=0
        assets.listFiles()?.filter{it.isDirectory&&!it.name.startsWith(".converting-")&&File(it,"model.gltf").isFile}.orEmpty().forEach{dir->
            val metadataFile=File(dir,"asset.json");val json=runCatching{JSONObject(metadataFile.readText())}.getOrElse{JSONObject().put("id",dir.name).put("displayName",dir.name)}
            if(json.has("triangleCount")&&json.has("contentHash")&&json.has("files")&&json.has("conversionProfile"))return@forEach
            runCatching{
                val details=GltfMetadataExtractor.extract(dir);val imageExt=setOf("png","jpg","jpeg","webp","bmp","tga","dds");val textures=dir.walkTopDown().filter{it.isFile&&it.name!="thumbnail.png"&&it.extension.lowercase() in imageExt}.toList()
                val bounds=if(details.boundsMin!=null&&details.boundsMax!=null)JSONObject().put("min",GltfMetadataExtractor.vectorJson(details.boundsMin)).put("max",GltfMetadataExtractor.vectorJson(details.boundsMax))else JSONObject.NULL
                val format=json.optString("sourceFormat").lowercase();val profile=when(format){"gltf","glb"->"preserve_gltf";"fbx","dae","blend"->"preserve_scene";"obj"->"surface_mesh";else->"static_geometry"}
                val inventory=JSONArray();AssetFingerprint.inventory(dir).filter{it.path!="asset.json"}.forEach{inventory.put(JSONObject().put("path",it.path).put("size",it.size).put("sha256",it.sha256))}
                json.put("schemaVersion",3).put("conversionProfile",profile).put("sourceUpAxis","unknown").put("sourceUnitScale",1.0).put("convertedUpAxis","source-preserved").put("convertedUnits","source-preserved").put("hasBones",false).put("assetKind",if(details.animationCount>0)"animated" else "static").put("cameraCount",0).put("lightCount",0).put("meshCount",details.meshCount).put("nodeCount",details.nodeCount).put("materialCount",details.materialCount).put("texturedMaterialCount",details.texturedMaterialCount).put("textureCount",maxOf(details.textureCount,textures.size)).put("textureFiles",JSONArray(textures.map{it.relativeTo(dir).invariantSeparatorsPath})).put("animationCount",details.animationCount).put("vertexCount",details.vertexCount).put("triangleCount",details.triangleCount).put("bounds",bounds).put("contentHash",AssetFingerprint.contentHash(dir)).put("files",inventory).put("warnings",JSONArray(details.warnings))
                val temp=File(dir,"asset.json.tmp");temp.writeText(json.toString());if(metadataFile.exists())metadataFile.delete();check(temp.renameTo(metadataFile));changed++
            }
        }
        return changed
    }
}
