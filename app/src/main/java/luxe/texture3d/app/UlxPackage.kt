package luxe.texture3d.app

import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** `.ulx` is an unencrypted binary ZIP container with a required text manifest. */
object UlxPackage {
    const val EXTENSION="ulx"
    const val MANIFEST="ulx/manifest.json"
    const val FORMAT_VERSION=1

    data class Info(val uid:String,val name:String,val format:Int)

    fun create(output:File,uid:String,name:String,files:Map<String,File>){
        output.parentFile?.mkdirs();val temp=File(output.parentFile,".${output.name}.tmp-${System.nanoTime()}")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use{zip->
            val manifest=JSONObject().put("ulx","project").put("format",FORMAT_VERSION).put("uid",uid).put("name",name).put("encrypted",false).put("entries",files.keys.sorted())
            zip.putNextEntry(ZipEntry(MANIFEST));zip.write(manifest.toString(2).toByteArray());zip.closeEntry()
            val buffer=ByteArray(256*1024)
            files.toSortedMap().forEach{(path,file)->require(file.isFile);require(!path.startsWith('/')&&!path.contains("..")){"Unsafe ULX entry"};zip.putNextEntry(ZipEntry("payload/$path"));FileInputStream(file).use{input->while(true){val n=input.read(buffer);if(n<0)break;zip.write(buffer,0,n)}};zip.closeEntry()}
        }
        check(temp.length()>0);if(output.exists())output.delete();check(temp.renameTo(output)){"Unable to finalize ULX package"}
    }

    fun inspect(file:File):Info=ZipFile(file).use{zip->val entry=zip.getEntry(MANIFEST)?:error("ULX manifest is missing");val json=JSONObject(zip.getInputStream(entry).bufferedReader().use{it.readText()});require(json.optString("ulx")=="project"){"Not a ULX project"};Info(json.getString("uid"),json.getString("name"),json.getInt("format"))}

    fun extractPayload(file:File,destination:File):Info{
        val info=inspect(file);destination.mkdirs();val root=destination.canonicalFile;var total=0L;var entries=0
        ZipFile(file).use{zip->val enumeration=zip.entries();while(enumeration.hasMoreElements()){val entry=enumeration.nextElement();if(!entry.name.startsWith("payload/")||entry.isDirectory)continue;entries++;require(entries<=10000){"ULX contains too many files"};val relative=entry.name.removePrefix("payload/");val out=File(destination,relative).canonicalFile;require(out.path==root.path||out.path.startsWith(root.path+File.separator)){"Unsafe ULX path"};out.parentFile?.mkdirs();zip.getInputStream(entry).use{input->FileOutputStream(out).use{output->val buffer=ByteArray(128*1024);while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=2L*1024L*1024L*1024L){"ULX payload is too large"};output.write(buffer,0,n)}}}}
        };return info
    }
}
