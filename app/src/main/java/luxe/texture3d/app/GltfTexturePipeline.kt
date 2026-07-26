package luxe.texture3d.app

import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/** Collects, extracts, validates, and rewrites glTF image resources. */
object GltfTexturePipeline {
    data class Report(val textureFiles:Int,val files:List<String>,val warnings:List<String>)
    private val imageExtensions=setOf("png","jpg","jpeg","webp","bmp","tga","dds")
    private const val MAX_EMBEDDED_BYTES=256L*1024L*1024L

    fun process(assetRoot:File,sourceRoot:File):Report {
        val model=File(assetRoot,"model.gltf");val json=JSONObject(model.readText());val images=json.optJSONArray("images")?:return Report(0,emptyList(),emptyList())
        val textureDir=File(assetRoot,"textures").apply{mkdirs()};val warnings=mutableListOf<String>();val hashes=mutableMapOf<String,File>();val outputFiles=linkedSetOf<String>();var written=0
        for(i in 0 until images.length()){
            val image=images.optJSONObject(i)?:continue
            if(image.has("bufferView")){warnings+="Image $i remains embedded in a bufferView";continue}
            val uri=image.optString("uri");if(uri.isBlank()){warnings+="Image $i has no URI";continue}
            val mime=image.optString("mimeType")
            val bytes:ByteArray;val suggested:String
            if(uri.startsWith("data:")){
                val comma=uri.indexOf(',');require(comma>0){"Image $i has malformed data URI"}
                val header=uri.substring(5,comma);require(header.contains(";base64")){"Image $i uses an unsupported non-base64 data URI"}
                val encoded=uri.substring(comma+1);require(encoded.length<=MAX_EMBEDDED_BYTES*4/3+16){"Embedded image $i is too large"}
                bytes=Base64.decode(encoded,Base64.DEFAULT);require(bytes.size<=MAX_EMBEDDED_BYTES){"Embedded image $i is too large"}
                suggested="embedded_$i.${extensionFor(header.substringBefore(';'),bytes)}"
            }else{
                val decoded=runCatching{URLDecoder.decode(uri,"UTF-8")}.getOrDefault(uri).replace('\\','/')
                val basename=decoded.substringAfterLast('/')
                val source=findResource(assetRoot,sourceRoot,decoded,basename)?:error("Missing texture referenced by glTF: $uri")
                require(source.length()<=MAX_EMBEDDED_BYTES){"Texture is too large: ${source.name}"}
                bytes=source.readBytes();suggested=source.name
            }
            require(bytes.isNotEmpty()){ "Image $i is empty" }
            val extension=extensionFor(mime,bytes,suggested.substringAfterLast('.',""));val hash=sha256(bytes)
            val target=hashes[hash]?:run{
                val base=sanitize(suggested.substringBeforeLast('.',"texture_$i"));var out=File(textureDir,"$base.$extension");var suffix=1
                while(out.exists()&&!out.readBytes().contentEquals(bytes)){out=File(textureDir,"$base-$suffix.$extension");suffix++}
                if(!out.exists())out.writeBytes(bytes)
                hashes[hash]=out;written++;out
            }
            validateImage(target,extension,warnings)
            outputFiles+="textures/${target.name}"
            image.put("uri","textures/${target.name}")
            if(mime.isBlank())image.put("mimeType",mimeFor(extension))
        }
        model.writeText(json.toString())
        // Delete duplicate loose image files after every URI points into textures/.
        assetRoot.walkTopDown().filter{it.isFile&&it.parentFile!=textureDir&&it.extension.lowercase() in imageExtensions}.toList().forEach{it.delete()}
        return Report(written,outputFiles.toList(),warnings.distinct())
    }

    private fun findResource(assetRoot:File,sourceRoot:File,decoded:String,basename:String):File?{
        val direct=File(assetRoot,decoded);if(direct.isFile)return direct
        val sourceDirect=File(sourceRoot,decoded);if(sourceDirect.isFile)return sourceDirect
        return sequenceOf(assetRoot,sourceRoot).flatMap{root->root.walkTopDown().asSequence()}.firstOrNull{it.isFile&&it.name.equals(basename,true)}
    }
    private fun validateImage(file:File,ext:String,warnings:MutableList<String>){
        if(ext in setOf("png","jpg","jpeg","webp","bmp")){val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,o);if(o.outWidth<=0||o.outHeight<=0)error("Texture cannot be decoded: ${file.name}")}
        else warnings+="${file.name} uses $ext; device support may vary"
    }
    private fun extensionFor(mime:String,bytes:ByteArray,fallback:String=""):String=when{
        mime.contains("png",true)||bytes.startsWith(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47))->"png"
        mime.contains("jpeg",true)||mime.contains("jpg",true)||(bytes.size>2&&bytes[0]==0xff.toByte()&&bytes[1]==0xd8.toByte())->"jpg"
        mime.contains("webp",true)||(bytes.size>12&&String(bytes,8,4)=="WEBP")->"webp"
        mime.contains("bmp",true)||(bytes.size>2&&bytes[0]==0x42.toByte()&&bytes[1]==0x4d.toByte())->"bmp"
        fallback.lowercase() in imageExtensions->fallback.lowercase()
        else->"bin"
    }
    private fun mimeFor(ext:String)=when(ext){"png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"bmp"->"image/bmp";"dds"->"image/vnd-ms.dds";else->"application/octet-stream"}
    private fun sanitize(v:String)=v.replace(Regex("[^A-Za-z0-9._-]"),"_").take(64).ifBlank{"texture"}
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun ByteArray.startsWith(prefix:ByteArray)=size>=prefix.size&&prefix.indices.all{this[it]==prefix[it]}
}
