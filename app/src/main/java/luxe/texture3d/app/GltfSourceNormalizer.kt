package luxe.texture3d.app

import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

object GltfSourceNormalizer {
    fun normalize(source:File,destination:File):JSONObject{
        require(source.isFile){"glTF source is missing"};require(source.length()>0){"${source.name} is empty"};require(source.length()<=64L*1024L*1024L){"glTF JSON is larger than 64 MB"}
        val bytes=source.readBytes();val text=decode(bytes).trim().trimEnd('\u0000').trim()
        require(text.isNotBlank()){"${source.name} contains no JSON data"}
        if(text.startsWith("version https://git-lfs"))error("${source.name} is a Git LFS pointer, not the model data")
        if(text.startsWith("<"))error("${source.name} contains XML/HTML instead of glTF JSON")
        require(text.startsWith("{")){"${source.name} is not a JSON glTF file (first byte 0x${bytes.first().toUByte().toString(16)})"}
        val json=runCatching{JSONObject(text)}.getOrElse{error("${source.name} contains malformed glTF JSON: ${it.message}")}
        require(json.optJSONObject("asset")!=null){"${source.name} has no glTF asset object"}
        destination.parentFile?.mkdirs();val temp=File(destination.parentFile,".${destination.name}.tmp");temp.writeText(json.toString());if(destination.exists())destination.delete();check(temp.renameTo(destination)){"Unable to stage normalized glTF"};check(destination.length()>0){"Normalized glTF is empty"};return json
    }
    private fun decode(bytes:ByteArray):String=when{
        bytes.size>=3&&bytes[0]==0xef.toByte()&&bytes[1]==0xbb.toByte()&&bytes[2]==0xbf.toByte()->String(bytes,3,bytes.size-3,Charsets.UTF_8)
        bytes.size>=2&&bytes[0]==0xff.toByte()&&bytes[1]==0xfe.toByte()->String(bytes,2,bytes.size-2,Charset.forName("UTF-16LE"))
        bytes.size>=2&&bytes[0]==0xfe.toByte()&&bytes[1]==0xff.toByte()->String(bytes,2,bytes.size-2,Charset.forName("UTF-16BE"))
        else->String(bytes,Charsets.UTF_8)
    }
}
