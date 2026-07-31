package luxe.texture3d.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object SafeZipExtractor {
    data class Limits(val maxDepth:Int=6,val maxEntries:Int=5000,val maxExpandedBytes:Long=768L*1024L*1024L)
    data class Result(val files:List<File>,val expandedBytes:Long)

    fun extractRecursive(source:File,destination:File,limits:Limits=Limits()):Result {
        var entries=0;var bytes=0L;val all=mutableListOf<File>()
        fun extract(zip:File,target:File,depth:Int){
            require(depth<=limits.maxDepth){"ZIP nesting exceeds ${limits.maxDepth} levels"}
            target.mkdirs();val canonicalRoot=target.canonicalFile
            ZipInputStream(FileInputStream(zip).buffered()).use{input->
                while(true){val entry=input.nextEntry?:break;entries++;require(entries<=limits.maxEntries){"ZIP contains too many files"}
                    val out=File(target,entry.name).canonicalFile
                    require(out.path==canonicalRoot.path||out.path.startsWith(canonicalRoot.path+File.separator)){"Unsafe ZIP path blocked"}
                    if(entry.isDirectory)out.mkdirs() else{out.parentFile?.mkdirs();FileOutputStream(out).use{o->val buffer=ByteArray(128*1024);while(true){val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=limits.maxExpandedBytes){"Expanded ZIP is too large"};o.write(buffer,0,n)}};all+=out}
                    input.closeEntry()
                }
            }
            target.walkTopDown().filter{it.isFile&&it.extension.lowercase() in setOf("zip","zae")}.toList().forEachIndexed{i,nested->extract(nested,File(nested.parentFile,"${nested.nameWithoutExtension}_unpacked_$i"),depth+1)}
        }
        extract(source,destination,0);return Result(all.distinct(),bytes)
    }
}
