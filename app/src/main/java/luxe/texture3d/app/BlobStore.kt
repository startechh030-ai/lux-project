package luxe.texture3d.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class BlobStore(context:Context){
    data class Stored(val hash:String,val file:File,val size:Long,val mime:String)
    private val fs=AppFileSystem(context)
    fun put(source:File,mime:String=mimeFor(source)):Stored{
        require(source.isFile){"Blob source is missing: ${source.name}"};val hash=sha256(source);val dir=File(fs.blobs,hash.take(2)).apply{mkdirs()};val target=File(dir,hash)
        if(!target.exists()){
            val linked=runCatching{java.nio.file.Files.createLink(target.toPath(),source.toPath());true}.getOrDefault(false)
            if(!linked){val temp=File(dir,".$hash.tmp-${System.nanoTime()}");FileInputStream(source).use{i->FileOutputStream(temp).use{i.copyTo(it,256*1024)}};check(temp.length()==source.length()){ "Blob copy is incomplete" };if(!temp.renameTo(target)){if(target.exists())temp.delete()else error("Unable to finalize blob")}}
        }
        return Stored(hash,target,target.length(),mime)
    }
    private fun sha256(file:File):String{val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024);FileInputStream(file).use{input->while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
    companion object{fun mimeFor(file:File)=when(file.extension.lowercase()){"gltf"->"model/gltf+json";"bin"->"application/octet-stream";"png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"hdr"->"image/vnd.radiance";"mp4"->"video/mp4";"webm"->"video/webm";"svg"->"image/svg+xml";else->"application/octet-stream"}}
}
