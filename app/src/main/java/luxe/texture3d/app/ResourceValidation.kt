package luxe.texture3d.app

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ResourceValidation {
    data class Result(val status:String,val messages:List<String>)
    fun asset(dir:File):Result{val report=GltfValidator.validate(dir);val messages=report.errors+report.warnings;val family=File(dir,"family.json");if(!family.isFile)return Result("Invalid",messages+"family.json is missing");val format=runCatching{JSONObject(family.readText()).optInt("format")}.getOrDefault(0);return Result(if(!report.valid)"Invalid" else if(messages.isNotEmpty()||format<2)"Valid with warnings" else "Valid",messages+(if(format<2)listOf("Family index needs migration")else emptyList()))}
    fun project(dir:File):Result{val ulx=dir.listFiles()?.firstOrNull{it.extension.equals("ulx",true)}?:return Result("Invalid",listOf("ULX package is missing"));return runCatching{val info=UlxPackage.inspect(ulx);Result("Valid",listOf("ULX ${info.format} • ${ulx.length()} bytes"))}.getOrElse{Result("Invalid",listOf(it.message?:"ULX validation failed"))}}
    fun descriptor(file:File):Result=runCatching{JSONObject(file.readText());Result("Valid",listOf("Text descriptor parsed successfully"))}.getOrElse{Result("Invalid",listOf(it.message?:"Invalid descriptor"))}
    fun ulelement(file:File):Result=runCatching{val json=JSONObject(file.readText());val root=file.parentFile;val failures=mutableListOf<String>();val files=json.optJSONArray("files");if(files!=null)for(i in 0 until files.length()){val entry=files.optJSONObject(i)?:continue;val payload=File(root,entry.optString("path")).canonicalFile;if(!payload.path.startsWith(root.canonicalPath+File.separator)||!payload.isFile)failures+="Missing/unsafe ${entry.optString("path")}" else if(entry.optString("sha256")!=sha256(payload))failures+="Checksum mismatch ${entry.optString("path")}"};Result(if(failures.isEmpty())"Valid" else "Invalid",failures.ifEmpty{listOf("All payload checksums match")})}.getOrElse{Result("Invalid",listOf(it.message?:"Invalid Ulelement"))}
    private fun sha256(file:File):String{val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(128*1024);FileInputStream(file).use{input->while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}
