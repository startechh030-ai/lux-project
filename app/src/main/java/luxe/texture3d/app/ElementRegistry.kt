package luxe.texture3d.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ElementRegistry(private val context:Context){
    private val fs=AppFileSystem(context);private val db=ElementDatabase.get(context);private val blobs=BlobStore(context)

    suspend fun migrateLegacyAssets():Int=withContext(Dispatchers.IO){
        var migrated=0
        fs.assets.listFiles()?.filter{it.isDirectory&&!it.name.startsWith(".converting-")&&File(it,"asset.json").isFile}.orEmpty().forEach{dir->if(registerModelAsset(dir)!=null)migrated++}
        migrated
    }

    suspend fun registerModelAsset(assetDir:File):ElementEntity?=withContext(Dispatchers.IO){
        val metadataFile=File(assetDir,"asset.json");if(!metadataFile.isFile)return@withContext null
        val metadata=JSONObject(metadataFile.readText());val existingUid=metadata.optString("elementUid")
        if(existingUid.isNotBlank()){
            val existing=db.elements().getElement(existingUid)
            if(existing!=null&&metadata.optInt("elementExtractionVersion",0)<2)runCatching{ModelElementExtractor(context).extract(assetDir,existing)}
            return@withContext existing
        }
        val name=metadata.optString("displayName",assetDir.name).substringBeforeLast('.').ifBlank{"Imported Model"};val elementUid="el-${UUID.randomUUID()}";val revisionUid="rev-${UUID.randomUUID()}";val now=System.currentTimeMillis();val contentHash=metadata.optString("contentHash").ifBlank{AssetFingerprint.contentHash(assetDir)}
        val refs=mutableListOf<ElementBlobRefEntity>();val blobRows=mutableListOf<BlobStore.Stored>()
        assetDir.walkTopDown().filter{it.isFile&&it.name!="asset.json"}.forEach{file->val stored=blobs.put(file);blobRows+=stored;refs+=ElementBlobRefEntity(revisionUid,stored.hash,file.relativeTo(assetDir).invariantSeparatorsPath,roleFor(file))}
        val revisionDir=File(fs.elements,"$elementUid/revisions/$revisionUid").apply{mkdirs()};val revisionManifest=File(revisionDir,"revision.ulelement")
        val payload=JSONArray();refs.forEach{payload.put(JSONObject().put("path",it.logicalPath).put("blob","sha256-${it.blobHash}").put("role",it.role))}
        revisionManifest.writeText(JSONObject().put("ulx","element_revision").put("format",1).put("uid",revisionUid).put("element",elementUid).put("type","model").put("version","1.0.0").put("contentHash",contentHash).put("payload",payload).put("metadata",metadata).put("dependencies",JSONArray()).toString(2))
        val rootManifest=File(fs.elements,"$elementUid/element.ulelement");rootManifest.parentFile?.mkdirs();rootManifest.writeText(JSONObject().put("ulx","element").put("format",1).put("uid",elementUid).put("name",name).put("type","model").put("scope","library").put("currentRevision",revisionUid).put("thumbnail",metadata.optString("thumbnail","thumbnail.png")).toString(2))
        val element=ElementEntity(elementUid,name,"model","library",null,revisionUid,File(assetDir,metadata.optString("thumbnail","thumbnail.png")).absolutePath,"ready",now,now)
        db.withTransaction{
            blobRows.distinctBy{it.hash}.forEach{stored->val old=db.blobs().get(stored.hash);if(old==null)db.blobs().put(BlobEntity(stored.hash,stored.file.relativeTo(fs.library).invariantSeparatorsPath,stored.size,stored.mime,1,now))else db.blobs().increment(stored.hash,1)}
            db.elements().putElement(element);db.elements().putRevision(ElementRevisionEntity(revisionUid,elementUid,"1.0.0",revisionManifest.relativeTo(fs.library).invariantSeparatorsPath,contentHash,now));db.elements().putBlobRefs(refs)
        }
        metadata.put("elementUid",elementUid).put("revisionUid",revisionUid).put("elementSchemaVersion",1);val temp=File(assetDir,"asset.json.element.tmp");temp.writeText(metadata.toString());metadataFile.delete();check(temp.renameTo(metadataFile))
        runCatching{ModelElementExtractor(context).extract(assetDir,element)}
        element
    }

    private fun roleFor(file:File)=when{file.name=="model.gltf"->"model";file.name=="model.bin"->"geometry";file.name=="thumbnail.png"->"thumbnail";file.parentFile?.name=="textures"->"texture";else->"resource"}
}
