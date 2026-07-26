package luxe.texture3d.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GltfMetadataExtractor {
    data class Metadata(
        val meshCount:Int,val nodeCount:Int,val materialCount:Int,val textureCount:Int,val animationCount:Int,
        val vertexCount:Long,val triangleCount:Long,val texturedMaterialCount:Int,
        val boundsMin:FloatArray?,val boundsMax:FloatArray?,val warnings:List<String>
    )

    fun extract(root:File):Metadata{
        val json=JSONObject(File(root,"model.gltf").readText());val meshes=json.optJSONArray("meshes")?:JSONArray();val accessors=json.optJSONArray("accessors")?:JSONArray();var vertices=0L;var triangles=0L
        var min:FloatArray?=null;var max:FloatArray?=null;val warnings=mutableListOf<String>()
        for(mi in 0 until meshes.length()){
            val primitives=meshes.optJSONObject(mi)?.optJSONArray("primitives")?:continue
            for(pi in 0 until primitives.length()){
                val primitive=primitives.optJSONObject(pi)?:continue;val positionIndex=primitive.optJSONObject("attributes")?.optInt("POSITION",-1)?:-1
                val position=accessors.optJSONObject(positionIndex);val vertexCount=position?.optLong("count",0)?:0;vertices+=vertexCount
                if(position!=null){val pMin=vector3(position.optJSONArray("min"));val pMax=vector3(position.optJSONArray("max"));if(pMin!=null&&pMax!=null){if(min==null){min=pMin;max=pMax}else for(i in 0..2){min!![i]=kotlin.math.min(min!![i],pMin[i]);max!![i]=kotlin.math.max(max!![i],pMax[i])}}else warnings+="POSITION accessor $positionIndex has no bounds"}
                val indices=primitive.optInt("indices",-1);val elementCount=if(indices>=0)accessors.optJSONObject(indices)?.optLong("count",0)?:0 else vertexCount
                triangles+=when(primitive.optInt("mode",4)){4->elementCount/3;5,6->(elementCount-2).coerceAtLeast(0);else->0}
            }
        }
        val materials=json.optJSONArray("materials")?:JSONArray();var textured=0
        for(i in 0 until materials.length()){val m=materials.optJSONObject(i)?:continue;val pbr=m.optJSONObject("pbrMetallicRoughness");if(pbr?.has("baseColorTexture")==true||pbr?.has("metallicRoughnessTexture")==true||m.has("normalTexture")||m.has("occlusionTexture")||m.has("emissiveTexture"))textured++}
        return Metadata(meshes.length(),json.optJSONArray("nodes")?.length()?:0,materials.length(),json.optJSONArray("textures")?.length()?:0,json.optJSONArray("animations")?.length()?:0,vertices,triangles,textured,min,max,warnings.distinct())
    }
    private fun vector3(a:JSONArray?):FloatArray?=if(a!=null&&a.length()>=3)floatArrayOf(a.optDouble(0).toFloat(),a.optDouble(1).toFloat(),a.optDouble(2).toFloat())else null
    fun vectorJson(v:FloatArray?):JSONArray?=v?.let{JSONArray().put(it[0].toDouble()).put(it[1].toDouble()).put(it[2].toDouble())}
}
