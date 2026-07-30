package luxe.texture3d.app

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class RigAnimationElementExtractor(private val context:Context){
    data class RigInfo(val element:ElementEntity,val skeletonHash:String,val joints:Set<Int>,val skeletonRoot:Int)
    data class Result(val rigs:List<ElementEntity>,val animations:List<ElementEntity>)
    private val fs=AppFileSystem(context);private val db=ElementDatabase.get(context);private val elements=db.elements()

    suspend fun extract(assetDir:File,model:ElementEntity,modelRevision:String):Result{
        val gltf=JSONObject(File(assetDir,"model.gltf").readText());val nodes=gltf.optJSONArray("nodes")?:JSONArray();val accessors=gltf.optJSONArray("accessors")?:JSONArray();val parents=parentMap(nodes);val rigInfos=mutableListOf<RigInfo>()
        val skins=gltf.optJSONArray("skins")?:JSONArray()
        for(index in 0 until skins.length())skins.optJSONObject(index)?.let{rigInfos+=registerRig(model,modelRevision,index,it,nodes,parents)}
        linkSkinnedGeometry(model,modelRevision,nodes,rigInfos)
        val animations=mutableListOf<ElementEntity>();val animationArray=gltf.optJSONArray("animations")?:JSONArray()
        for(index in 0 until animationArray.length())animationArray.optJSONObject(index)?.let{animations+=registerAnimation(model,modelRevision,index,it,accessors,rigInfos)}
        return Result(rigInfos.map{it.element},animations)
    }

    private suspend fun registerRig(model:ElementEntity,modelRevision:String,index:Int,skin:JSONObject,nodes:JSONArray,parents:IntArray):RigInfo{
        val jointArray=skin.optJSONArray("joints")?:JSONArray();val jointSet=(0 until jointArray.length()).map{jointArray.optInt(it,-1)}.filter{it>=0}.toSet();val skeletonRoot=skin.optInt("skeleton",jointSet.firstOrNull()?:-1);val jointData=JSONArray()
        jointSet.forEach{nodeIndex->val node=nodes.optJSONObject(nodeIndex);jointData.put(JSONObject().put("node",nodeIndex).put("name",node?.optString("name").orEmpty()).put("parentNode",parents.getOrElse(nodeIndex){-1}).put("parentJoint",nearestJointParent(nodeIndex,parents,jointSet)))}
        val inverseAccessor=skin.optInt("inverseBindMatrices",-1);val signature=sha256(JSONObject().put("joints",jointData).put("root",skeletonRoot).put("inverseBindMatricesAccessor",inverseAccessor).toString());val uid=deterministic("${model.uid}|rig|$index|$signature");elements.getElement(uid)?.let{return RigInfo(it,signature,jointSet,skeletonRoot)}
        val revisionUid=revision(uid,signature);val now=System.currentTimeMillis();val name=skin.optString("name").ifBlank{"Rig ${index+1}"};val dir=File(fs.elements,"$uid/revisions/$revisionUid").apply{mkdirs()};val manifest=File(dir,"revision.ulelement");val payload=JSONObject().put("sourceModel",model.uid).put("sourceRevision",modelRevision).put("skinIndex",index).put("skeletonRoot",skeletonRoot).put("jointCount",jointSet.size).put("joints",jointData).put("inverseBindMatricesAccessor",inverseAccessor).put("skeletonHash",signature)
        manifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",uid).put("type","rig").put("version","1.0.0").put("contentHash",signature).put("payload",payload).put("dependencies",JSONArray()).toString(2));writeRoot(uid,name,"rig",model.uid,revisionUid)
        val entity=ElementEntity(uid,name,"rig","dependency",null,revisionUid,null,"ready",now,now);db.withTransaction{elements.putElement(entity);elements.putRevision(ElementRevisionEntity(revisionUid,uid,"1.0.0",manifest.relativeTo(fs.library).invariantSeparatorsPath,signature,now));elements.putDependencies(listOf(ElementDependencyEntity(deterministic("$modelRevision|rig|$uid"),modelRevision,uid,revisionUid,"rig",true)))};return RigInfo(entity,signature,jointSet,skeletonRoot)
    }

    private suspend fun registerAnimation(model:ElementEntity,modelRevision:String,index:Int,animation:JSONObject,accessors:JSONArray,rigs:List<RigInfo>):ElementEntity{
        val samplers=animation.optJSONArray("samplers")?:JSONArray();val channels=animation.optJSONArray("channels")?:JSONArray();val channelData=JSONArray();var duration=0.0;val targetedNodes=mutableSetOf<Int>();val pathCounts=mutableMapOf<String,Int>()
        for(ci in 0 until channels.length()){
            val channel=channels.optJSONObject(ci)?:continue;val samplerIndex=channel.optInt("sampler",-1);val sampler=samplers.optJSONObject(samplerIndex);val target=channel.optJSONObject("target");val node=target?.optInt("node",-1)?:-1;val path=target?.optString("path").orEmpty();if(node>=0)targetedNodes+=node;pathCounts[path]=(pathCounts[path]?:0)+1
            val input=sampler?.optInt("input",-1)?:-1;val output=sampler?.optInt("output",-1)?:-1;val max=accessors.optJSONObject(input)?.optJSONArray("max")?.optDouble(0,0.0)?:0.0;duration=kotlin.math.max(duration,max)
            channelData.put(JSONObject().put("channel",ci).put("sampler",samplerIndex).put("targetNode",node).put("path",path).put("inputAccessor",input).put("outputAccessor",output).put("interpolation",sampler?.optString("interpolation","LINEAR")))
        }
        val rig=rigs.maxByOrNull{info->info.joints.count{it in targetedNodes}}?.takeIf{info->info.joints.any{it in targetedNodes}}
        val signature=sha256(JSONObject().put("channels",channelData).put("duration",duration).put("rig",rig?.skeletonHash).toString());val uid=deterministic("${model.uid}|animation|$index|$signature");elements.getElement(uid)?.let{return it};val revisionUid=revision(uid,signature);val now=System.currentTimeMillis();val name=animation.optString("name").ifBlank{"Animation ${index+1}"};val dir=File(fs.elements,"$uid/revisions/$revisionUid").apply{mkdirs()};val manifest=File(dir,"revision.ulelement");val rootMotion=rig?.let{r->channelData.asSequence().any{entry->entry.optInt("targetNode")==r.skeletonRoot&&entry.optString("path")=="translation"}}?:false
        val payload=JSONObject().put("sourceModel",model.uid).put("sourceRevision",modelRevision).put("animationIndex",index).put("durationSeconds",duration).put("channelCount",channels.length()).put("samplerCount",samplers.length()).put("channels",channelData).put("translationChannels",pathCounts["translation"]?:0).put("rotationChannels",pathCounts["rotation"]?:0).put("scaleChannels",pathCounts["scale"]?:0).put("weightChannels",pathCounts["weights"]?:0).put("requiredSkeletonHash",rig?.skeletonHash?:JSONObject.NULL).put("rootMotionCandidate",rootMotion)
        val deps=JSONArray();rig?.let{deps.put(JSONObject().put("element",it.element.uid).put("revision",it.element.currentRevisionUid).put("role","rig").put("required",true))};manifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",uid).put("type","animation").put("version","1.0.0").put("contentHash",signature).put("payload",payload).put("dependencies",deps).toString(2));writeRoot(uid,name,"animation",model.uid,revisionUid)
        val entity=ElementEntity(uid,name,"animation","dependency",null,revisionUid,null,"ready",now,now);val dependencies=mutableListOf(ElementDependencyEntity(deterministic("$modelRevision|animation|$uid"),modelRevision,uid,revisionUid,"animation",false));rig?.let{dependencies+=ElementDependencyEntity(deterministic("$revisionUid|rig|${it.element.uid}"),revisionUid,it.element.uid,it.element.currentRevisionUid,"rig",true)};db.withTransaction{elements.putElement(entity);elements.putRevision(ElementRevisionEntity(revisionUid,uid,"1.0.0",manifest.relativeTo(fs.library).invariantSeparatorsPath,signature,now));elements.putDependencies(dependencies)};return entity
    }

    private suspend fun linkSkinnedGeometry(model:ElementEntity,modelRevision:String,nodes:JSONArray,rigs:List<RigInfo>){
        for(i in 0 until nodes.length()){
            val node=nodes.optJSONObject(i)?:continue;val mesh=node.optInt("mesh",-1);val skin=node.optInt("skin",-1);if(mesh<0||skin !in rigs.indices)continue
            val geometryUid=deterministic("${model.uid}|geometry|$mesh");val geometry=elements.getElement(geometryUid)?:continue;val rig=rigs[skin].element
            elements.putDependencies(listOf(ElementDependencyEntity(deterministic("${geometry.currentRevisionUid}|rig|${rig.uid}"),geometry.currentRevisionUid,rig.uid,rig.currentRevisionUid,"rig",true)))
        }
    }

    private fun parentMap(nodes:JSONArray)=IntArray(nodes.length()){-1}.also{parents->for(i in 0 until nodes.length()){val children=nodes.optJSONObject(i)?.optJSONArray("children")?:continue;for(c in 0 until children.length()){val child=children.optInt(c,-1);if(child in parents.indices)parents[child]=i}}}
    private fun nearestJointParent(node:Int,parents:IntArray,joints:Set<Int>):Int{var p=parents.getOrElse(node){-1};while(p>=0){if(p in joints)return p;p=parents.getOrElse(p){-1}};return -1}
    private fun writeRoot(uid:String,name:String,type:String,sourceModel:String,revision:String){File(fs.elements,"$uid/element.ulelement").apply{parentFile?.mkdirs();writeText(JSONObject().put("ulx","element").put("format",1).put("uid",uid).put("name",name).put("type",type).put("scope","dependency").put("sourceModel",sourceModel).put("currentRevision",revision).toString(2))}}
    private fun deterministic(seed:String)="el-${UUID.nameUUIDFromBytes(seed.toByteArray())}"
    private fun revision(uid:String,hash:String)="rev-${UUID.nameUUIDFromBytes("$uid|$hash".toByteArray())}"
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun JSONArray.asSequence()=sequence{for(i in 0 until length())optJSONObject(i)?.let{yield(it)}}
}
