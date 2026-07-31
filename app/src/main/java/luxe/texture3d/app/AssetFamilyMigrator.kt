package luxe.texture3d.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object AssetFamilyMigrator {
    private val executor=Executors.newSingleThreadExecutor()
    fun migrateAsync(context:Context,onComplete:()->Unit={}){executor.execute{runCatching{migrate(context)};onComplete()}}
    fun migrate(context:Context):Int{
        val fs=AppFileSystem(context);var count=0
        fs.assets.listFiles()?.filter{it.isDirectory&&!it.name.startsWith(".converting-")&&File(it,"model.gltf").isFile&&File(it,"asset.json").isFile}.orEmpty().forEach{dir->
            val family=File(dir,"family.json");val format=if(family.isFile)runCatching{JSONObject(family.readText()).optInt("format",0)}.getOrDefault(0)else 0
            if(format<2)runCatching{AssetFamilyBuilder(context).build(dir);count++}
        }
        return count
    }
}
