package luxe.texture3d.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Text family manifest used only for project-created reusable data. */
object UlelementFormat {
    data class Entry(val type:String,val name:String,val relativePath:String)
    fun write(familyFolder:File,name:String,sourceProjectUid:String,entries:List<Entry>):File{
        familyFolder.mkdirs();val files=JSONArray();entries.forEach{entry->val file=File(familyFolder,entry.relativePath).canonicalFile;require(file.isFile&&file.path.startsWith(familyFolder.canonicalPath+File.separator)){"Invalid Ulelement payload"};files.put(JSONObject().put("type",entry.type).put("name",entry.name).put("path",entry.relativePath.replace(File.separatorChar,'/')).put("sha256",sha256(file)))}
        val manifest=File(familyFolder,"${safe(name)}.ulelement");manifest.writeText(JSONObject().put("ulx","element_family").put("format",1).put("uid","ule-${UUID.randomUUID()}").put("name",name).put("createdFromProject",sourceProjectUid).put("tree",JSONArray(entries.map{JSONObject().put("type",it.type).put("name",it.name).put("path",it.relativePath)})).put("files",files).toString(2));return manifest
    }
    private fun sha256(file:File):String{val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(128*1024);FileInputStream(file).use{i->while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
    private fun safe(v:String)=v.replace(Regex("[^A-Za-z0-9._-]"),"_").take(64).ifBlank{"Element"}
}
