package luxe.texture3d.app

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Owns multiple glTF assets inside ModelViewer's existing Engine and Scene. */
class EditorSceneManager(private val engine:Engine,private val scene:Scene,private val onChanged:()->Unit={},private val onSelectionChanged:(Record?)->Unit={}){
    data class Record(val uid:String,var name:String,val source:String,val asset:FilamentAsset,val loader:ResourceLoader,val worldCenter:FloatArray,val worldHalfExtent:FloatArray,var visible:Boolean=true,var locked:Boolean=false,var added:Boolean=false)
    private val materialProvider:MaterialProvider=UbershaderProvider(engine)
    private val assetLoader=AssetLoader(engine,materialProvider,EntityManager.get())
    private val records=linkedMapOf<String,Record>()
    private val entityOwners=mutableMapOf<Int,String>()
    private var selectedUid:String?=null
    private val ready=IntArray(256)
    val size:Int get()=records.size
    val selection:String? get()=selectedUid
    fun all():List<Record> = records.values.toList()
    fun selected():Record?=selectedUid?.let(records::get)

    fun addGlb(buffer:Buffer,name:String,source:String,uid:String="instance-${UUID.randomUUID()}"):Record{
        val asset=assetLoader.createAsset(buffer)?:error("Unable to parse GLB: $name")
        return begin(asset,uid,name,source,null)
    }

    fun addGltf(assetFolder:File,name:String=assetFolder.name,uid:String="instance-${UUID.randomUUID()}"):Record{
        val model=File(assetFolder,"model.gltf");require(model.isFile){"model.gltf is missing"};val json=direct(model)
        val asset=assetLoader.createAsset(json)?:error("Unable to parse glTF: $name");val loader=ResourceLoader(engine,true)
        for(uri in asset.resourceUris){val file=resolve(assetFolder,uri);require(file.isFile){"Missing glTF resource: $uri"};loader.addResourceData(uri,direct(file))}
        return begin(asset,uid,name,assetFolder.absolutePath,loader)
    }

    private fun begin(asset:FilamentAsset,uid:String,name:String,source:String,provided:ResourceLoader?):Record{
        require(uid !in records){"Duplicate scene instance UID"};val loader=provided?:ResourceLoader(engine,true);loader.asyncBeginLoad(asset);asset.releaseSourceData();val placement=ModelPlacement.placeOnGround(engine,asset)
        val record=Record(uid,name,source,asset,loader,placement.worldCenter,placement.worldHalfExtent);records[uid]=record;asset.renderableEntities.forEach{entityOwners[it]=uid};onChanged();return record
    }

    fun update(){
        records.values.forEach{record->record.loader.asyncUpdateLoad();if(record.visible){while(true){val count=record.asset.popRenderables(ready);if(count<=0)break;scene.addEntities(ready.copyOf(count));record.added=true};if(record.added)scene.addEntities(record.asset.lightEntities)}}
    }

    fun selectByEntity(entity:Int):Boolean{val uid=entityOwners[entity]?:run{clearSelection();return false};return select(uid)}
    fun select(uid:String):Boolean{val record=records[uid]?:return false;if(record.locked||!record.visible)return false;if(selectedUid==uid)return true;selectedUid=uid;onSelectionChanged(record);onChanged();return true}
    fun clearSelection(){if(selectedUid==null)return;selectedUid=null;onSelectionChanged(null);onChanged()}

    fun setVisible(uid:String,visible:Boolean){val r=records[uid]?:return;if(r.visible==visible)return;r.visible=visible;if(visible){scene.addEntities(r.asset.entities);scene.addEntities(r.asset.lightEntities)}else{scene.removeEntities(r.asset.entities);scene.removeEntities(r.asset.lightEntities);if(selectedUid==uid)clearSelection()};onChanged()}
    fun setLocked(uid:String,locked:Boolean){records[uid]?.let{it.locked=locked;if(locked&&selectedUid==uid)clearSelection();onChanged()}}
    fun rename(uid:String,name:String){records[uid]?.let{it.name=name;onChanged()}}
    fun remove(uid:String){val r=records.remove(uid)?:return;if(selectedUid==uid){selectedUid=null;onSelectionChanged(null)};r.asset.renderableEntities.forEach{entityOwners.remove(it)};scene.removeEntities(r.asset.entities);scene.removeEntities(r.asset.lightEntities);r.loader.asyncCancelLoad();r.loader.evictResourceData();r.loader.destroy();assetLoader.destroyAsset(r.asset);onChanged()}
    fun removeLast():Boolean{val uid=records.keys.lastOrNull()?:return false;remove(uid);return true}

    fun serialize():JSONArray=JSONArray().apply{records.values.forEach{r->put(JSONObject().put("instanceUid",r.uid).put("name",r.name).put("source",r.source).put("visible",r.visible).put("locked",r.locked).put("transform",JSONObject().put("position",JSONArray().put(0).put(0).put(0)).put("rotation",JSONArray().put(0).put(0).put(0).put(1)).put("scale",JSONArray().put(1).put(1).put(1))))}}

    fun destroy(){records.keys.toList().forEach(::remove);assetLoader.destroy();materialProvider.destroyMaterials();materialProvider.destroy()}

    private fun direct(file:File):ByteBuffer{require(file.length()<=Int.MAX_VALUE);val bytes=file.readBytes();return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply{put(bytes);flip()}}
    private fun resolve(root:File,uri:String):File{val decoded=java.net.URLDecoder.decode(uri,"UTF-8").replace('\\','/');val file=File(root,decoded).canonicalFile;require(file.path.startsWith(root.canonicalPath+File.separator)){"Unsafe glTF resource URI"};return file}
}
