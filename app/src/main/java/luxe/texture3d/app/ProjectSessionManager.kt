package luxe.texture3d.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProjectSessionManager(context:Context,projectPath:String){
    data class OpenResult(val projectUid:String,val name:String,val recoveryAvailable:Boolean,val modelFile:File?)
    private val fs=AppFileSystem(context);val projectDir=File(projectPath);private val ulxFile:File
    lateinit var sessionDir:File;private set
    var dirty=false;private set
    lateinit var info:UlxPackage.Info;private set

    init{require(projectDir.isDirectory){"Project folder is missing"};ulxFile=projectDir.listFiles()?.firstOrNull{it.extension.equals("ulx",true)}?:ProjectPackageManager.rebuild(projectDir)}

    fun open():OpenResult{
        info=UlxPackage.inspect(ulxFile);sessionDir=File(fs.runtimeCache,"Sessions/${info.uid}");val recovery=File(sessionDir,"session.lock").isFile&&File(sessionDir,"autosave/scene.json").isFile
        if(!recovery)extractFresh();File(sessionDir,"session.lock").apply{parentFile?.mkdirs();writeText(JSONObject().put("projectUid",info.uid).put("openedAt",System.currentTimeMillis()).toString())}
        return OpenResult(info.uid,info.name,recovery,File(sessionDir,"model.glb").takeIf{it.isFile})
    }
    fun recoverAutosave(){val auto=File(sessionDir,"autosave");File(auto,"scene.json").takeIf{it.isFile}?.copyTo(File(sessionDir,"scene.json"),true);File(auto,"project.json").takeIf{it.isFile}?.copyTo(File(sessionDir,"project.json"),true);dirty=true}
    fun openLastSaved(){extractFresh();File(sessionDir,"session.lock").writeText(JSONObject().put("projectUid",info.uid).put("openedAt",System.currentTimeMillis()).toString());dirty=false}
    fun markDirty(){dirty=true}
    fun scene():JSONObject{val file=File(sessionDir,"scene.json");if(!file.isFile)file.writeText(defaultScene().toString(2));return JSONObject(file.readText())}
    fun writeScene(scene:JSONObject){File(sessionDir,"scene.json").writeText(scene.toString(2));markDirty()}
    fun addResourceReference(path:String,type:String){val scene=scene();val key=if(type=="ulelement")"ulelements" else "libraryReferences";val array=scene.optJSONArray(key)?:JSONArray().also{scene.put(key,it)};for(i in 0 until array.length())if(array.optJSONObject(i)?.optString("path")==path)return;array.put(JSONObject().put("path",path).put("type",type).put("addedAt",System.currentTimeMillis()));writeScene(scene)}
    fun autosave(){if(!dirty)return;val auto=File(sessionDir,"autosave").apply{mkdirs()};File(sessionDir,"scene.json").takeIf{it.isFile}?.copyTo(File(auto,"scene.json"),true);File(sessionDir,"project.json").takeIf{it.isFile}?.copyTo(File(auto,"project.json"),true);File(auto,"autosave.json").writeText(JSONObject().put("projectUid",info.uid).put("savedAt",System.currentTimeMillis()).toString())}
    fun save(){
        val projectJson=File(sessionDir,"project.json");require(projectJson.isFile);val sceneFile=File(sessionDir,"scene.json").apply{if(!isFile)writeText(defaultScene().toString(2))};val payload=linkedMapOf<String,File>()
        sessionDir.walkTopDown().filter{it.isFile&&it.name!="session.lock"&&!it.relativeTo(sessionDir).invariantSeparatorsPath.startsWith("autosave/")}.forEach{payload[it.relativeTo(sessionDir).invariantSeparatorsPath]=it}
        val next=File(projectDir,".${projectDir.name}.ulx.next");UlxPackage.create(next,info.uid,JSONObject(projectJson.readText()).optString("name",info.name),payload);UlxPackage.inspect(next)
        val backup=File(projectDir,"${ulxFile.name}.backup");if(backup.exists())backup.delete();if(ulxFile.exists())require(ulxFile.renameTo(backup));if(!next.renameTo(ulxFile)){backup.renameTo(ulxFile);error("Unable to replace ULX package")};backup.delete();projectJson.copyTo(File(projectDir,"project.json"),true);File(sessionDir,"autosave").deleteRecursively();dirty=false
    }
    fun closeDiscard(){sessionDir.deleteRecursively();dirty=false}
    fun closeClean(){File(sessionDir,"session.lock").delete();if(!dirty)sessionDir.deleteRecursively()}

    private fun extractFresh(){sessionDir.deleteRecursively();sessionDir.mkdirs();UlxPackage.extractPayload(ulxFile,sessionDir);if(!File(sessionDir,"scene.json").isFile)File(sessionDir,"scene.json").writeText(defaultScene().toString(2));File(sessionDir,"autosave").mkdirs()}
    private fun defaultScene()=JSONObject().put("format",1).put("instances",JSONArray()).put("selection",JSONObject.NULL).put("camera",JSONObject()).put("ulelements",JSONArray())
}
