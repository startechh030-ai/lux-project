package luxe.texture3d.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class TrashManager(context:Context){
    private val fs=AppFileSystem(context)
    fun move(source:File,type:String,friendlyName:String):File{
        require(source.exists());val bucket=File(fs.libraryTrash,type).apply{mkdirs()};val target=File(bucket,"${source.name}-${UUID.randomUUID()}");require(source.renameTo(target)){"Unable to move item to Trash"};File(target,"trash.json").writeText(JSONObject().put("originalPath",source.absolutePath).put("type",type).put("name",friendlyName).put("deletedAt",System.currentTimeMillis()).toString(2));return target
    }
    fun restore(trashed:File):File{val metadata=JSONObject(File(trashed,"trash.json").readText());val original=File(metadata.getString("originalPath"));original.parentFile?.mkdirs();require(!original.exists()){"Original location already exists"};File(trashed,"trash.json").delete();require(trashed.renameTo(original)){"Unable to restore item"};return original}
    fun deletePermanently(trashed:File)=trashed.deleteRecursively()
    fun entries():List<File> = fs.libraryTrash.walkTopDown().maxDepth(2).filter{it.isDirectory&&File(it,"trash.json").isFile}.toList()
}
