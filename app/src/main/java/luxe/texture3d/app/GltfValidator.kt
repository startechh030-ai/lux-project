package luxe.texture3d.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/** Structural and filesystem safety validation for Assimp-generated glTF 2.0. */
object GltfValidator {
    data class Report(
        val valid:Boolean,
        val errors:List<String>,
        val warnings:List<String>,
        val meshCount:Int=0,
        val nodeCount:Int=0,
        val materialCount:Int=0,
        val textureCount:Int=0,
        val animationCount:Int=0
    )

    fun validate(root:File):Report {
        val errors=mutableListOf<String>();val warnings=mutableListOf<String>()
        val model=File(root,"model.gltf")
        if(!model.isFile||model.length()==0L)return Report(false,listOf("model.gltf is missing or empty"),emptyList())
        val json=runCatching{JSONObject(model.readText())}.getOrElse{return Report(false,listOf("model.gltf contains invalid JSON: ${it.message}"),emptyList())}
        val version=json.optJSONObject("asset")?.optString("version").orEmpty()
        if(!version.startsWith("2"))errors+="glTF asset.version must be 2.0"
        val meshes=json.optJSONArray("meshes")?:JSONArray();if(meshes.length()==0)errors+="glTF contains no meshes"
        val nodes=json.optJSONArray("nodes")?:JSONArray();val scenes=json.optJSONArray("scenes")?:JSONArray()
        if(scenes.length()==0)warnings+="glTF contains no explicit scene"
        checkNodeReferences(nodes,scenes,errors)
        val buffers=json.optJSONArray("buffers")?:JSONArray();val views=json.optJSONArray("bufferViews")?:JSONArray();val accessors=json.optJSONArray("accessors")?:JSONArray()
        val bufferFiles=Array<File?>(buffers.length()){null}
        for(i in 0 until buffers.length()){
            val b=buffers.optJSONObject(i)?:continue;val uri=b.optString("uri")
            if(uri.isBlank()){errors+="Buffer $i has no URI";continue}
            if(uri.startsWith("data:")){warnings+="Buffer $i is embedded as a data URI";continue}
            val file=resolveSafe(root,uri,errors,"buffer $i")
            if(file!=null){bufferFiles[i]=file;if(!file.isFile||file.length()==0L)errors+="Buffer file is missing or empty: $uri" else if(b.optLong("byteLength",0)>file.length())errors+="Buffer $i declares more bytes than its file contains"}
        }
        for(i in 0 until views.length()){
            val v=views.optJSONObject(i)?:continue;val bi=v.optInt("buffer",-1);if(bi !in 0 until buffers.length())errors+="bufferView $i references invalid buffer $bi"
            val offset=v.optLong("byteOffset",0);val length=v.optLong("byteLength",-1);if(offset<0||length<0)errors+="bufferView $i has invalid offset/length"
            bufferFiles.getOrNull(bi)?.let{if(offset+length>it.length())errors+="bufferView $i exceeds buffer file bounds"}
        }
        val validTypes=setOf("SCALAR","VEC2","VEC3","VEC4","MAT2","MAT3","MAT4");val componentTypes=setOf(5120,5121,5122,5123,5125,5126)
        for(i in 0 until accessors.length()){
            val a=accessors.optJSONObject(i)?:continue;val view=a.optInt("bufferView",-1);if(view>=views.length())errors+="Accessor $i references invalid bufferView $view"
            if(a.optLong("count",0)<=0)errors+="Accessor $i has invalid count"
            if(a.optString("type") !in validTypes)errors+="Accessor $i has invalid type"
            if(a.optInt("componentType",0) !in componentTypes)errors+="Accessor $i has invalid componentType"
        }
        val images=json.optJSONArray("images")?:JSONArray()
        for(i in 0 until images.length()){
            val image=images.optJSONObject(i)?:continue;val uri=image.optString("uri")
            if(uri.isBlank()){if(!image.has("bufferView"))warnings+="Image $i has neither URI nor bufferView";continue}
            if(uri.startsWith("data:"))continue
            resolveSafe(root,uri,errors,"image $i")?.let{if(!it.isFile||it.length()==0L)errors+="Image file is missing or empty: $uri"}
        }
        val materials=json.optJSONArray("materials")?.length()?:0
        val textures=json.optJSONArray("textures")?.length()?:0
        val animations=json.optJSONArray("animations")?.length()?:0
        return Report(errors.isEmpty(),errors,warnings,meshes.length(),nodes.length(),materials,textures,animations)
    }

    private fun resolveSafe(root:File,uri:String,errors:MutableList<String>,label:String):File?{
        if(uri.contains('\\')){errors+="$label uses a backslash URI: $uri";return null}
        val decoded=runCatching{URLDecoder.decode(uri,"UTF-8")}.getOrDefault(uri)
        if(decoded.startsWith("/")||Regex("^[A-Za-z]:").containsMatchIn(decoded)||decoded.contains("://")){errors+="$label uses an absolute URI: $uri";return null}
        val canonicalRoot=root.canonicalFile;val resolved=runCatching{File(root,decoded).canonicalFile}.getOrElse{errors+="$label has an invalid URI: $uri";return null}
        if(resolved.path!=canonicalRoot.path&&!resolved.path.startsWith(canonicalRoot.path+File.separator)){errors+="$label escapes the asset folder: $uri";return null}
        return resolved
    }

    private fun checkNodeReferences(nodes:JSONArray,scenes:JSONArray,errors:MutableList<String>){
        for(i in 0 until nodes.length()){val children=nodes.optJSONObject(i)?.optJSONArray("children")?:continue;for(j in 0 until children.length()){val child=children.optInt(j,-1);if(child !in 0 until nodes.length())errors+="Node $i references invalid child $child"}}
        for(i in 0 until scenes.length()){val roots=scenes.optJSONObject(i)?.optJSONArray("nodes")?:continue;for(j in 0 until roots.length()){val node=roots.optInt(j,-1);if(node !in 0 until nodes.length())errors+="Scene $i references invalid node $node"}}
    }
}
