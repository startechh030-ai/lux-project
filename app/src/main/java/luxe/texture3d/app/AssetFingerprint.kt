package luxe.texture3d.app

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object AssetFingerprint {
    data class FileRecord(val path:String,val size:Long,val sha256:String)

    fun fileHash(file:File)=digestFiles(listOf(file))
    fun contentHash(root:File):String{
        val model=File(root,"model.gltf");val selected=linkedSetOf(model)
        runCatching{JSONObject(model.readText())}.getOrNull()?.let{json->
            listOf(json.optJSONArray("buffers"),json.optJSONArray("images")).forEach{array->if(array!=null)for(i in 0 until array.length()){val uri=array.optJSONObject(i)?.optString("uri").orEmpty();if(uri.isNotBlank()&&!uri.startsWith("data:")){val decoded=java.net.URLDecoder.decode(uri,"UTF-8");File(root,decoded).takeIf{it.isFile}?.let(selected::add)}}}
        }
        val files=selected.filter{it.isFile}.sortedBy{it.relativeTo(root).invariantSeparatorsPath}
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        files.forEach{file->digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray());FileInputStream(file).use{input->while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}}
        return digest.digest().hex()
    }
    fun inventory(root:File):List<FileRecord> = root.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(root).invariantSeparatorsPath}.map{FileRecord(it.relativeTo(root).invariantSeparatorsPath,it.length(),fileHash(it))}.toList()
    fun findDuplicate(assetsRoot:File,hash:String):File?=assetsRoot.listFiles()?.firstOrNull{dir->dir.isDirectory&&!dir.name.startsWith(".converting-")&&runCatching{JSONObject(File(dir,"asset.json").readText()).optString("contentHash")==hash}.getOrDefault(false)}
    private fun digestFiles(files:List<File>):String{val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(256*1024);files.forEach{FileInputStream(it).use{i->while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)}}};return d.digest().hex()}
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}
}
