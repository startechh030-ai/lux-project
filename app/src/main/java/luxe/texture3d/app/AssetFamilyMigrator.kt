package luxe.texture3d.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

object AssetFamilyMigrator {
    private val executor=Executors.newSingleThreadExecutor()
    fun migrateAsync(context:Context,onComplete:()->Unit={}){executor.execute{runCatching{migrate(context)};onComplete()}}
    fun migrate(context:Context):Int{val fs=AppFileSystem(context);var count=0;fs.assets.listFiles()?.filter{it.isDirectory&&!it.name.startsWith(".converting-")&&File(it,"model.gltf").isFile&&File(it,"asset.json").isFile&&!File(it,"family.json").isFile}.orEmpty().forEach{runCatching{AssetFamilyBuilder(context).build(it);count++}};return count}
}
