package luxe.texture3d.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Extracts reusable Texture and Material Elements from a validated Model Element. */
class ModelElementExtractor(private val context:Context){
    private val fs=AppFileSystem(context);private val db=ElementDatabase.get(context);private val elements=db.elements();private val blobDao=db.blobs();private val blobs=BlobStore(context)

    suspend fun extract(assetDir:File,model:ElementEntity){
        val metadata=JSONObject(File(assetDir,"asset.json").readText());val modelRevision=metadata.optString("revisionUid",model.currentRevisionUid);val gltf=JSONObject(File(assetDir,"model.gltf").readText())
        ensureSystemShaders()
        val imageElements=mutableMapOf<Int,ElementEntity>();val images=gltf.optJSONArray("images")?:JSONArray()
        for(i in 0 until images.length()){
            val image=images.optJSONObject(i)?:continue;val uri=image.optString("uri");if(uri.isBlank()||uri.startsWith("data:")||image.has("bufferView"))continue
            val file=File(assetDir,uri);if(!file.isFile)continue
            val usage=usageForImage(i,gltf);val name=textureName(file.nameWithoutExtension,usage);val uid=deterministic("${model.uid}|texture|$i|${metadata.optString("contentHash")}")
            val texture=registerTexture(uid,name,file,usage,"dependency",model.uid);imageElements[i]=texture
        }
        val textures=gltf.optJSONArray("textures")?:JSONArray();val textureIndexToElement=mutableMapOf<Int,ElementEntity>()
        for(i in 0 until textures.length()){val source=textures.optJSONObject(i)?.optInt("source",-1)?:-1;imageElements[source]?.let{textureIndexToElement[i]=it}}
        val materials=gltf.optJSONArray("materials")?:JSONArray();val materialElements=linkedMapOf<Int,ElementEntity>()
        for(i in 0 until materials.length()){
            val material=materials.optJSONObject(i)?:continue;materialElements[i]=registerMaterial(model,modelRevision,i,material,textureIndexToElement)
        }
        val geometryElements=GeometryElementExtractor(context).extract(assetDir,model,modelRevision,materialElements)
        val rigAnimation=RigAnimationElementExtractor(context).extract(assetDir,model,modelRevision)
        updateModelRevisionManifest(model,modelRevision,materialElements.values.toList(),imageElements.values.toList(),geometryElements,rigAnimation.rigs,rigAnimation.animations)
        metadata.put("elementExtractionVersion",3).put("childTextureElements",JSONArray(imageElements.values.map{it.uid})).put("childMaterialElements",JSONArray(materialElements.values.map{it.uid})).put("childGeometryElements",JSONArray(geometryElements.map{it.uid})).put("childRigElements",JSONArray(rigAnimation.rigs.map{it.uid})).put("childAnimationElements",JSONArray(rigAnimation.animations.map{it.uid}));val tmp=File(assetDir,"asset.json.extract.tmp");tmp.writeText(metadata.toString());File(assetDir,"asset.json").delete();check(tmp.renameTo(File(assetDir,"asset.json")))
    }

    suspend fun registerStandaloneTexture(file:File,displayName:String):ElementEntity{
        ensureSystemShaders();val usage=inferUsage(displayName);val hash=AssetFingerprint.fileHash(file);val uid="el-${UUID.randomUUID()}";return registerTexture(uid,displayName.substringBeforeLast('.'),file,usage,"library",null)
    }

    private suspend fun registerTexture(uid:String,name:String,file:File,usage:String,scope:String,sourceModel:String?):ElementEntity{
        elements.getElement(uid)?.let{return it};val stored=blobs.put(file);val revisionUid=deterministic("$uid|${stored.hash}");val now=System.currentTimeMillis();val dimensions=imageDimensions(file)
        val revisionDir=File(fs.elements,"$uid/revisions/$revisionUid").apply{mkdirs()};val revisionFile=File(revisionDir,"revision.ulelement")
        revisionFile.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",uid).put("type","texture").put("version","1.0.0").put("contentHash",stored.hash).put("payload",JSONObject().put("blob","sha256-${stored.hash}").put("mime",stored.mime).put("width",dimensions.first).put("height",dimensions.second).put("usage",usage)).put("dependencies",JSONArray()).toString(2))
        val root=File(fs.elements,"$uid/element.ulelement");root.parentFile?.mkdirs();root.writeText(JSONObject().put("ulx","element").put("format",1).put("uid",uid).put("name",name).put("type","texture").put("scope",scope).put("sourceModel",sourceModel?:JSONObject.NULL).put("currentRevision",revisionUid).toString(2))
        val entity=ElementEntity(uid,name,"texture",scope,null,revisionUid,stored.file.absolutePath,"ready",now,now)
        db.withTransaction{val old=blobDao.get(stored.hash);if(old==null)blobDao.put(BlobEntity(stored.hash,stored.file.relativeTo(fs.library).invariantSeparatorsPath,stored.size,stored.mime,1,now))else blobDao.increment(stored.hash,1);elements.putElement(entity);elements.putRevision(ElementRevisionEntity(revisionUid,uid,"1.0.0",revisionFile.relativeTo(fs.library).invariantSeparatorsPath,stored.hash,now));elements.putBlobRefs(listOf(ElementBlobRefEntity(revisionUid,stored.hash,file.name,"texture")))}
        return entity
    }

    private suspend fun registerMaterial(model:ElementEntity,modelRevision:String,index:Int,material:JSONObject,textures:Map<Int,ElementEntity>):ElementEntity{
        val uid=deterministic("${model.uid}|material|$index");elements.getElement(uid)?.let{return it}
        val name=material.optString("name").ifBlank{"Material ${index+1}"};val bindings=textureBindings(material,textures);val shaderUid=when{material.optJSONObject("extensions")?.has("KHR_materials_unlit")==true->"system.shader.unlit";material.optString("alphaMode","OPAQUE")=="BLEND"->"system.shader.transparent-pbr";else->"system.shader.standard-pbr"}
        val canonical=JSONObject().put("material",material).put("bindings",JSONArray(bindings.map{JSONObject().put("role",it.first).put("element",it.second.uid).put("revision",it.second.currentRevisionUid)})).put("shader",shaderUid).toString();val hash=sha256(canonical);val revisionUid=deterministic("$uid|$hash");val now=System.currentTimeMillis();val dir=File(fs.elements,"$uid/revisions/$revisionUid").apply{mkdirs()};val manifest=File(dir,"revision.ulelement")
        val dependencies=JSONArray().put(JSONObject().put("element",shaderUid).put("revision",elements.getElement(shaderUid)?.currentRevisionUid).put("role","shader").put("required",true));bindings.forEach{dependencies.put(JSONObject().put("element",it.second.uid).put("revision",it.second.currentRevisionUid).put("role",it.first).put("required",false))}
        manifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",uid).put("type","material").put("version","1.0.0").put("contentHash",hash).put("payload",material).put("dependencies",dependencies).toString(2));File(fs.elements,"$uid/element.ulelement").apply{parentFile?.mkdirs();writeText(JSONObject().put("ulx","element").put("format",1).put("uid",uid).put("name",name).put("type","material").put("scope","dependency").put("sourceModel",model.uid).put("currentRevision",revisionUid).toString(2))}
        val entity=ElementEntity(uid,name,"material","dependency",null,revisionUid,null,"ready",now,now);val deps=mutableListOf(ElementDependencyEntity(deterministic("$revisionUid|shader"),revisionUid,shaderUid,elements.getElement(shaderUid)!!.currentRevisionUid,"shader",true));bindings.forEach{deps+=ElementDependencyEntity(deterministic("$revisionUid|${it.first}|${it.second.uid}"),revisionUid,it.second.uid,it.second.currentRevisionUid,it.first,false)};deps+=ElementDependencyEntity(deterministic("$modelRevision|material|$uid"),modelRevision,uid,revisionUid,"material",true)
        db.withTransaction{elements.putElement(entity);elements.putRevision(ElementRevisionEntity(revisionUid,uid,"1.0.0",manifest.relativeTo(fs.library).invariantSeparatorsPath,hash,now));elements.putDependencies(deps)}
        return entity
    }

    private suspend fun updateModelRevisionManifest(model:ElementEntity,revisionUid:String,materials:List<ElementEntity>,textures:List<ElementEntity>,geometries:List<ElementEntity>,rigs:List<ElementEntity>,animations:List<ElementEntity>){
        val revision=elements.revisions(model.uid).firstOrNull{it.uid==revisionUid}?:return;val file=File(fs.library,revision.manifestPath);if(!file.isFile)return
        val json=JSONObject(file.readText());val dependencies=JSONArray()
        materials.forEach{dependencies.put(JSONObject().put("element",it.uid).put("revision",it.currentRevisionUid).put("role","material").put("required",true))}
        textures.forEach{texture->dependencies.put(JSONObject().put("element",texture.uid).put("revision",texture.currentRevisionUid).put("role","texture_dependency").put("required",false))}
        geometries.forEach{geometry->dependencies.put(JSONObject().put("element",geometry.uid).put("revision",geometry.currentRevisionUid).put("role","geometry").put("required",true))}
        rigs.forEach{rig->dependencies.put(JSONObject().put("element",rig.uid).put("revision",rig.currentRevisionUid).put("role","rig").put("required",true))}
        animations.forEach{animation->dependencies.put(JSONObject().put("element",animation.uid).put("revision",animation.currentRevisionUid).put("role","animation").put("required",false))}
        json.put("dependencies",dependencies).put("extractionVersion",3);val temp=File(file.parentFile,"${file.name}.tmp");temp.writeText(json.toString(2));file.delete();check(temp.renameTo(file))
    }

    private suspend fun ensureSystemShaders(){listOf("system.shader.standard-pbr" to "Luxe Standard PBR","system.shader.unlit" to "Luxe Unlit","system.shader.transparent-pbr" to "Luxe Transparent PBR").forEach{(uid,name)->if(elements.getElement(uid)==null){val rev="$uid.r1";val now=System.currentTimeMillis();val dir=File(fs.elements,"$uid/revisions/$rev").apply{mkdirs()};val manifest=File(dir,"revision.ulelement");manifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",rev).put("element",uid).put("type","shader").put("version","1.0.0").put("system",true).put("backend","hidden").toString(2));File(fs.elements,"$uid/element.ulelement").apply{parentFile?.mkdirs();writeText(JSONObject().put("ulx","element").put("format",1).put("uid",uid).put("name",name).put("type","shader").put("scope","system").put("currentRevision",rev).toString(2))};elements.putElement(ElementEntity(uid,name,"shader","system",null,rev,null,"readonly",now,now));elements.putRevision(ElementRevisionEntity(rev,uid,"1.0.0",manifest.relativeTo(fs.library).invariantSeparatorsPath,sha256(name),now))}}}

    private fun textureBindings(m:JSONObject,textures:Map<Int,ElementEntity>):List<Pair<String,ElementEntity>>{val out=mutableListOf<Pair<String,ElementEntity>>();fun add(role:String,o:JSONObject?){val index=o?.optInt("index",-1)?:-1;textures[index]?.let{out+=role to it}};val p=m.optJSONObject("pbrMetallicRoughness");add("baseColor",p?.optJSONObject("baseColorTexture"));add("metallicRoughness",p?.optJSONObject("metallicRoughnessTexture"));add("normal",m.optJSONObject("normalTexture"));add("occlusion",m.optJSONObject("occlusionTexture"));add("emissive",m.optJSONObject("emissiveTexture"));return out}
    private fun usageForImage(image:Int,gltf:JSONObject):String{val textures=gltf.optJSONArray("textures")?:return "unknown";val map=mutableMapOf<Int,Int>();for(i in 0 until textures.length())map[i]=textures.optJSONObject(i)?.optInt("source",-1)?:-1;val materials=gltf.optJSONArray("materials")?:return "unknown";for(i in 0 until materials.length()){val m=materials.optJSONObject(i)?:continue;textureBindingsRaw(m).forEach{(role,index)->if(map[index]==image)return role}};return "unknown"}
    private fun textureBindingsRaw(m:JSONObject):List<Pair<String,Int>>{val out=mutableListOf<Pair<String,Int>>();fun add(r:String,o:JSONObject?){val i=o?.optInt("index",-1)?:-1;if(i>=0)out+=r to i};val p=m.optJSONObject("pbrMetallicRoughness");add("baseColor",p?.optJSONObject("baseColorTexture"));add("metallicRoughness",p?.optJSONObject("metallicRoughnessTexture"));add("normal",m.optJSONObject("normalTexture"));add("occlusion",m.optJSONObject("occlusionTexture"));add("emissive",m.optJSONObject("emissiveTexture"));return out}
    private fun inferUsage(name:String):String{val n=name.lowercase();return when{Regex("normal|nrm|_nor").containsMatchIn(n)->"normal";Regex("base.?color|albedo|diffuse").containsMatchIn(n)->"baseColor";"rough" in n->"roughness";"metal" in n->"metallic";Regex("(^|[_-])ao([_.-]|$)|occlusion").containsMatchIn(n)->"occlusion";"emiss" in n->"emissive";"mask" in n->"mask";else->"unknown"}}
    private fun textureName(base:String,usage:String)=if(usage=="unknown")base else "$base (${usage.replaceFirstChar{it.uppercase()}})"
    private fun imageDimensions(file:File):Pair<Int,Int>{val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,o);return o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)}
    private fun deterministic(seed:String)="el-${UUID.nameUUIDFromBytes(seed.toByteArray())}"
    private fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
