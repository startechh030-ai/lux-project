package luxe.texture3d.app

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Creates one dependency-scoped Geometry Element per glTF mesh. */
class GeometryElementExtractor(private val context:Context){
    private val fs=AppFileSystem(context);private val db=ElementDatabase.get(context);private val elements=db.elements()

    suspend fun extract(assetDir:File,model:ElementEntity,modelRevision:String,materials:Map<Int,ElementEntity>):List<ElementEntity>{
        val gltf=JSONObject(File(assetDir,"model.gltf").readText());val meshes=gltf.optJSONArray("meshes")?:JSONArray();val accessors=gltf.optJSONArray("accessors")?:JSONArray();val output=mutableListOf<ElementEntity>()
        for(meshIndex in 0 until meshes.length()){
            val mesh=meshes.optJSONObject(meshIndex)?:continue;val uid=deterministic("${model.uid}|geometry|$meshIndex");val existing=elements.getElement(uid);if(existing!=null){output+=existing;continue}
            val primitives=mesh.optJSONArray("primitives")?:JSONArray();val primitiveJson=JSONArray();val materialDeps=linkedMapOf<Int,ElementEntity>();var vertices=0L;var triangles=0L;var morphTargets=0;var min:FloatArray?=null;var max:FloatArray?=null
            for(pi in 0 until primitives.length()){
                val primitive=primitives.optJSONObject(pi)?:continue;val attrs=primitive.optJSONObject("attributes")?:JSONObject();val positionIndex=attrs.optInt("POSITION",-1);val position=accessors.optJSONObject(positionIndex);val vertexCount=position?.optLong("count",0)?:0;vertices+=vertexCount
                val indices=primitive.optInt("indices",-1);val elementCount=if(indices>=0)accessors.optJSONObject(indices)?.optLong("count",0)?:0 else vertexCount;val mode=primitive.optInt("mode",4);val tri=when(mode){4->elementCount/3;5,6->(elementCount-2).coerceAtLeast(0);else->0};triangles+=tri
                val pMin=vec3(position?.optJSONArray("min"));val pMax=vec3(position?.optJSONArray("max"));if(pMin!=null&&pMax!=null){if(min==null){min=pMin;max=pMax}else for(i in 0..2){min!![i]=kotlin.math.min(min!![i],pMin[i]);max!![i]=kotlin.math.max(max!![i],pMax[i])}}
                val materialIndex=primitive.optInt("material",-1);materials[materialIndex]?.let{materialDeps[pi]=it};val targetCount=primitive.optJSONArray("targets")?.length()?:0;morphTargets+=targetCount
                primitiveJson.put(JSONObject().put("index",pi).put("mode",mode).put("vertexCount",vertexCount).put("triangleCount",tri).put("indicesAccessor",indices).put("materialIndex",materialIndex).put("morphTargetCount",targetCount).put("attributes",attributeSummary(attrs)))
            }
            val topology=sha256(JSONObject().put("mesh",meshIndex).put("primitives",primitiveJson).toString());val revisionUid="rev-${UUID.nameUUIDFromBytes("$uid|$topology".toByteArray())}";val now=System.currentTimeMillis();val name=mesh.optString("name").ifBlank{"Mesh ${meshIndex+1}"};val dir=File(fs.elements,"$uid/revisions/$revisionUid").apply{mkdirs()};val manifest=File(dir,"revision.ulelement");val dependencies=JSONArray();materialDeps.forEach{(index,material)->dependencies.put(JSONObject().put("element",material.uid).put("revision",material.currentRevisionUid).put("role","material:primitive:$index").put("required",false))}
            val payload=JSONObject().put("sourceModel",model.uid).put("sourceRevision",modelRevision).put("meshIndex",meshIndex).put("primitiveCount",primitives.length()).put("vertexCount",vertices).put("triangleCount",triangles).put("morphTargetCount",morphTargets).put("topologySignature",topology).put("bounds",bounds(min,max)).put("primitives",primitiveJson)
            manifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",uid).put("type","geometry").put("version","1.0.0").put("contentHash",topology).put("payload",payload).put("dependencies",dependencies).toString(2));File(fs.elements,"$uid/element.ulelement").apply{parentFile?.mkdirs();writeText(JSONObject().put("ulx","element").put("format",1).put("uid",uid).put("name",name).put("type","geometry").put("scope","dependency").put("sourceModel",model.uid).put("currentRevision",revisionUid).toString(2))}
            val entity=ElementEntity(uid,name,"geometry","dependency",null,revisionUid,null,"ready",now,now);val deps=materialDeps.map{(index,material)->ElementDependencyEntity(deterministic("$revisionUid|material|$index"),revisionUid,material.uid,material.currentRevisionUid,"material:primitive:$index",false)}+ElementDependencyEntity(deterministic("$modelRevision|geometry|$uid"),modelRevision,uid,revisionUid,"geometry",true)
            db.withTransaction{elements.putElement(entity);elements.putRevision(ElementRevisionEntity(revisionUid,uid,"1.0.0",manifest.relativeTo(fs.library).invariantSeparatorsPath,topology,now));elements.putDependencies(deps)};output+=entity
        }
        return output
    }

    private fun attributeSummary(a:JSONObject)=JSONObject().apply{listOf("POSITION","NORMAL","TANGENT","TEXCOORD_0","TEXCOORD_1","COLOR_0","JOINTS_0","WEIGHTS_0").forEach{key->if(a.has(key))put(key.lowercase(),a.optInt(key,-1))}}
    private fun bounds(min:FloatArray?,max:FloatArray?)=if(min!=null&&max!=null)JSONObject().put("min",JSONArray().put(min[0]).put(min[1]).put(min[2])).put("max",JSONArray().put(max[0]).put(max[1]).put(max[2]))else JSONObject.NULL
    private fun vec3(a:JSONArray?):FloatArray?=if(a!=null&&a.length()>=3)floatArrayOf(a.optDouble(0).toFloat(),a.optDouble(1).toFloat(),a.optDouble(2).toFloat())else null
    private fun deterministic(seed:String)="el-${UUID.nameUUIDFromBytes(seed.toByteArray())}"
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}
