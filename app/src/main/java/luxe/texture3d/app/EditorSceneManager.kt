package luxe.texture3d.app

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import android.opengl.Matrix
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
    data class Record(val uid:String,var name:String,val source:String,val asset:FilamentAsset,val loader:ResourceLoader,val baseCenter:FloatArray,val baseHalfExtent:FloatArray,val baseMatrix:FloatArray,val position:FloatArray=floatArrayOf(0f,0f,0f),val rotation:FloatArray=floatArrayOf(0f,0f,0f,1f),val scale:FloatArray=floatArrayOf(1f,1f,1f),var worldCenter:FloatArray=baseCenter.copyOf(),var worldHalfExtent:FloatArray=baseHalfExtent.copyOf(),var visible:Boolean=true,var locked:Boolean=false,var added:Boolean=false)
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
        val record=Record(uid,name,source,asset,loader,placement.worldCenter,placement.worldHalfExtent,placement.baseMatrix);records[uid]=record;asset.renderableEntities.forEach{entityOwners[it]=uid};onChanged();return record
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
    fun setTransform(uid:String,position:FloatArray,rotation:FloatArray,scale:FloatArray){val r=records[uid]?:return;if(r.locked)return;position.copyInto(r.position,0,0,3);rotation.copyInto(r.rotation,0,0,4);scale.copyInto(r.scale,0,0,3);applyTransform(r);if(selectedUid==uid)onSelectionChanged(r);onChanged()}
    fun resetTransform(uid:String)=setTransform(uid,floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f,1f),floatArrayOf(1f,1f,1f))
    private fun applyTransform(r:Record){val t=FloatArray(16);Matrix.setIdentityM(t,0);Matrix.translateM(t,0,r.position[0],r.position[1],r.position[2]);val rot=quaternionMatrix(r.rotation);val sm=FloatArray(16);Matrix.setIdentityM(sm,0);Matrix.scaleM(sm,0,r.scale[0],r.scale[1],r.scale[2]);val rs=FloatArray(16);val trs=FloatArray(16);val final=FloatArray(16);Matrix.multiplyMM(rs,0,rot,0,sm,0);Matrix.multiplyMM(trs,0,t,0,rs,0);Matrix.multiplyMM(final,0,trs,0,r.baseMatrix,0);val tm=engine.transformManager;tm.setTransform(tm.getInstance(r.asset.root),final);val center4=floatArrayOf(r.baseCenter[0],r.baseCenter[1],r.baseCenter[2],1f);val out=FloatArray(4);Matrix.multiplyMV(out,0,trs,0,center4,0);r.worldCenter=floatArrayOf(out[0],out[1],out[2]);val h=floatArrayOf(r.baseHalfExtent[0]*kotlin.math.abs(r.scale[0]),r.baseHalfExtent[1]*kotlin.math.abs(r.scale[1]),r.baseHalfExtent[2]*kotlin.math.abs(r.scale[2]));r.worldHalfExtent=floatArrayOf(kotlin.math.abs(rot[0])*h[0]+kotlin.math.abs(rot[4])*h[1]+kotlin.math.abs(rot[8])*h[2],kotlin.math.abs(rot[1])*h[0]+kotlin.math.abs(rot[5])*h[1]+kotlin.math.abs(rot[9])*h[2],kotlin.math.abs(rot[2])*h[0]+kotlin.math.abs(rot[6])*h[1]+kotlin.math.abs(rot[10])*h[2])}
    private fun quaternionMatrix(q:FloatArray):FloatArray{val x=q[0];val y=q[1];val z=q[2];val w=q[3];val n=kotlin.math.sqrt(x*x+y*y+z*z+w*w).coerceAtLeast(1e-6f);val nx=x/n;val ny=y/n;val nz=z/n;val nw=w/n;return floatArrayOf(1-2*ny*ny-2*nz*nz,2*nx*ny+2*nz*nw,2*nx*nz-2*ny*nw,0f,2*nx*ny-2*nz*nw,1-2*nx*nx-2*nz*nz,2*ny*nz+2*nx*nw,0f,2*nx*nz+2*ny*nw,2*ny*nz-2*nx*nw,1-2*nx*nx-2*ny*ny,0f,0f,0f,0f,1f)}
    fun remove(uid:String){val r=records.remove(uid)?:return;if(selectedUid==uid){selectedUid=null;onSelectionChanged(null)};r.asset.renderableEntities.forEach{entityOwners.remove(it)};scene.removeEntities(r.asset.entities);scene.removeEntities(r.asset.lightEntities);r.loader.asyncCancelLoad();r.loader.evictResourceData();r.loader.destroy();assetLoader.destroyAsset(r.asset);onChanged()}
    fun removeLast():Boolean{val uid=records.keys.lastOrNull()?:return false;remove(uid);return true}

    fun serialize():JSONArray=JSONArray().apply{records.values.forEach{r->put(JSONObject().put("instanceUid",r.uid).put("name",r.name).put("source",r.source).put("visible",r.visible).put("locked",r.locked).put("transform",JSONObject().put("position",JSONArray(r.position.toList())).put("rotation",JSONArray(r.rotation.toList())).put("scale",JSONArray(r.scale.toList()))))}}

    fun destroy(){records.keys.toList().forEach(::remove);assetLoader.destroy();materialProvider.destroyMaterials();materialProvider.destroy()}

    private fun direct(file:File):ByteBuffer{require(file.length()<=Int.MAX_VALUE);val bytes=file.readBytes();return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply{put(bytes);flip()}}
    private fun resolve(root:File,uri:String):File{val decoded=java.net.URLDecoder.decode(uri,"UTF-8").replace('\\','/');val file=File(root,decoded).canonicalFile;require(file.path.startsWith(root.canonicalPath+File.separator)){"Unsafe glTF resource URI"};return file}
}
