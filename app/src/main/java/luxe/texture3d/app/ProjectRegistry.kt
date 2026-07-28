package luxe.texture3d.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectRegistry(context:Context){
    private val fs=AppFileSystem(context);private val dao=ElementDatabase.get(context).projects()
    suspend fun migrateWorkingProjects():Int=withContext(Dispatchers.IO){var count=0;fs.projects.listFiles()?.filter{it.isDirectory}.orEmpty().forEach{dir->val metaFile=File(dir,"project.json");val json=runCatching{JSONObject(metaFile.readText())}.getOrElse{JSONObject().put("name",dir.name)};val uid=json.optString("uid").ifBlank{"project-${UUID.randomUUID()}"};if(!json.has("uid")){json.put("uid",uid).put("ulxFormat",1);metaFile.writeText(json.toString())};dao.put(UlxProjectEntity(uid,json.optString("name",dir.name),File(dir,"${dir.name}.ulx").absolutePath,File(dir,"thumbnail.png").takeIf{it.isFile}?.absolutePath,dir.lastModified(),System.currentTimeMillis(),"working"));count++};count}
}
