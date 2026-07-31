package luxe.texture3d.app

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Builds imported Asset family metadata and clones reusable files into typed Library folders. */
class AssetFamilyBuilder(private val context:Context){
    private val fs=AppFileSystem(context);private val blobs=BlobStore(context)

    fun build(assetDir:File):JSONObject{
        val assetMetaFile=File(assetDir,"asset.json");val assetMeta=JSONObject(assetMetaFile.readText());val assetUid=assetMeta.optString("id",assetDir.name);val gltf=JSONObject(File(assetDir,"model.gltf").readText())
        val textures=extractTextures(assetDir,assetUid,gltf);val meshes=extractMeshes(gltf);val materials=extractMaterials(gltf,textures);val animations=extractAnimations(assetUid,gltf);val cameras=extractCameras(gltf);val lights=extractLights(gltf)
        val family=JSONObject().put("format",1).put("assetUid",assetUid).put("name",assetMeta.optString("displayName",assetDir.name)).put("geometry",meshes).put("materials",materials).put("textures",textures).put("animations",animations).put("cameras",cameras).put("lights",lights).put("createdAt",System.currentTimeMillis())
        File(assetDir,"family.json").writeText(family.toString(2))
        assetMeta.put("family","family.json").put("familyFormat",1)
        assetMeta.remove("elementUid");assetMeta.remove("revisionUid");assetMeta.remove("elementSchemaVersion")
        val temp=File(assetDir,"asset.json.family.tmp");temp.writeText(assetMeta.toString());assetMetaFile.delete();check(temp.renameTo(assetMetaFile));return family
    }

    fun importStandaloneImage(source:File,displayName:String):String{
        val uid="texture-${UUID.randomUUID()}";val stored=blobs.put(source);val folder=File(fs.libraryTextures,uid).apply{mkdirs()};val ext=source.extension.ifBlank{"bin"};val payload=File(folder,"source.$ext");linkOrCopy(stored.file,payload);val dimensions=imageDimensions(source);val usage=inferUsage(displayName)
        File(folder,"${safe(displayName.substringBeforeLast('.'))}.texture").writeText(JSONObject().put("format",1).put("uid",uid).put("name",displayName.substringBeforeLast('.')).put("type","texture").put("usage",usage).put("blob","sha256-${stored.hash}").put("file",payload.name).put("mime",stored.mime).put("width",dimensions.first).put("height",dimensions.second).put("createdAt",System.currentTimeMillis()).toString(2));return uid
    }

    private fun extractTextures(assetDir:File,assetUid:String,gltf:JSONObject):JSONArray{
        val output=JSONArray();val images=gltf.optJSONArray("images")?:return output;val folder=File(fs.libraryTextures,assetUid).apply{mkdirs()}
        for(i in 0 until images.length()){
            val image=images.optJSONObject(i)?:continue;val uri=image.optString("uri");if(uri.isBlank()||uri.startsWith("data:")||image.has("bufferView"))continue;val source=File(assetDir,uri);if(!source.isFile)continue
            val stored=blobs.put(source);val safeName=safe(source.name);val target=File(folder,safeName);if(!target.exists())linkOrCopy(stored.file,target);val usage=usageForImage(i,gltf);val descriptor=File(folder,"${safe(source.nameWithoutExtension)}.texture");val dimensions=imageDimensions(source)
            descriptor.writeText(JSONObject().put("format",1).put("uid","texture-$assetUid-$i").put("name",source.nameWithoutExtension).put("type","texture").put("usage",usage).put("blob","sha256-${stored.hash}").put("file",target.name).put("mime",stored.mime).put("width",dimensions.first).put("height",dimensions.second).put("sourceAsset",assetUid).toString(2))
            output.put(JSONObject().put("index",i).put("uid","texture-$assetUid-$i").put("usage",usage).put("libraryPath",descriptor.relativeTo(fs.library).invariantSeparatorsPath).put("blob","sha256-${stored.hash}"))
        };return output
    }

    private fun extractMeshes(gltf:JSONObject):JSONArray{val out=JSONArray();val meshes=gltf.optJSONArray("meshes")?:return out;val accessors=gltf.optJSONArray("accessors")?:JSONArray();for(i in 0 until meshes.length()){val mesh=meshes.optJSONObject(i)?:continue;val primitives=mesh.optJSONArray("primitives")?:JSONArray();var vertices=0L;var triangles=0L;for(p in 0 until primitives.length()){val primitive=primitives.optJSONObject(p)?:continue;val position=primitive.optJSONObject("attributes")?.optInt("POSITION",-1)?:-1;val vc=accessors.optJSONObject(position)?.optLong("count",0)?:0;vertices+=vc;val indices=primitive.optInt("indices",-1);val count=if(indices>=0)accessors.optJSONObject(indices)?.optLong("count",0)?:0 else vc;triangles+=if(primitive.optInt("mode",4)==4)count/3 else 0};out.put(JSONObject().put("index",i).put("name",mesh.optString("name").ifBlank{"Mesh ${i+1}"}).put("primitiveCount",primitives.length()).put("vertexCount",vertices).put("triangleCount",triangles))};return out}
    private fun extractMaterials(gltf:JSONObject,textures:JSONArray):JSONArray{val out=JSONArray();val materials=gltf.optJSONArray("materials")?:return out;for(i in 0 until materials.length()){val material=materials.optJSONObject(i)?:continue;out.put(JSONObject().put("index",i).put("name",material.optString("name").ifBlank{"Material ${i+1}"}).put("data",material))};return out}
    private fun extractAnimations(assetUid:String,gltf:JSONObject):JSONArray{val out=JSONArray();val animations=gltf.optJSONArray("animations")?:return out;val folder=File(fs.libraryAnimation,assetUid).apply{mkdirs()};for(i in 0 until animations.length()){val animation=animations.optJSONObject(i)?:continue;val name=animation.optString("name").ifBlank{"Animation ${i+1}"};val file=File(folder,"${safe(name)}.anim");file.writeText(JSONObject().put("format",1).put("uid","anim-$assetUid-$i").put("name",name).put("sourceAsset",assetUid).put("animationIndex",i).put("samplers",animation.optJSONArray("samplers")?:JSONArray()).put("channels",animation.optJSONArray("channels")?:JSONArray()).toString(2));out.put(JSONObject().put("index",i).put("uid","anim-$assetUid-$i").put("name",name).put("libraryPath",file.relativeTo(fs.library).invariantSeparatorsPath))};return out}
    private fun extractCameras(gltf:JSONObject)=gltf.optJSONArray("cameras")?:JSONArray()
    private fun extractLights(gltf:JSONObject)=gltf.optJSONObject("extensions")?.optJSONObject("KHR_lights_punctual")?.optJSONArray("lights")?:JSONArray()
    private fun linkOrCopy(source:File,target:File){target.parentFile?.mkdirs();val linked=runCatching{java.nio.file.Files.createLink(target.toPath(),source.toPath());true}.getOrDefault(false);if(!linked)source.copyTo(target,true)}
    private fun imageDimensions(file:File):Pair<Int,Int>{val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,o);return o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)}
    private fun usageForImage(image:Int,gltf:JSONObject):String{val textures=gltf.optJSONArray("textures")?:return "unknown";val map=mutableMapOf<Int,Int>();for(i in 0 until textures.length())map[i]=textures.optJSONObject(i)?.optInt("source",-1)?:-1;val materials=gltf.optJSONArray("materials")?:return "unknown";for(i in 0 until materials.length()){val m=materials.optJSONObject(i)?:continue;val p=m.optJSONObject("pbrMetallicRoughness");listOf("baseColor" to p?.optJSONObject("baseColorTexture"),"metallicRoughness" to p?.optJSONObject("metallicRoughnessTexture"),"normal" to m.optJSONObject("normalTexture"),"occlusion" to m.optJSONObject("occlusionTexture"),"emissive" to m.optJSONObject("emissiveTexture")).forEach{(role,slot)->if(map[slot?.optInt("index",-1)]==image)return role}};return "unknown"}
    private fun inferUsage(name:String):String{val n=name.lowercase();return when{Regex("normal|nrm|_nor").containsMatchIn(n)->"normal";Regex("base.?color|albedo|diffuse").containsMatchIn(n)->"baseColor";"rough" in n->"roughness";"metal" in n->"metallic";Regex("(^|[_-])ao([_.-]|$)|occlusion").containsMatchIn(n)->"occlusion";"emiss" in n->"emissive";"mask" in n->"mask";else->"unknown"}}
    private fun safe(value:String)=value.replace(Regex("[^A-Za-z0-9._-]"),"_").take(64).ifBlank{"resource"}
}
