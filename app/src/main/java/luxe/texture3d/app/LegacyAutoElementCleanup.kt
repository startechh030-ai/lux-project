package luxe.texture3d.app

import android.content.Context
import androidx.room.withTransaction
import java.io.File

object LegacyAutoElementCleanup {
    suspend fun runOnce(context:Context){
        val prefs=context.getSharedPreferences("library_migrations",Context.MODE_PRIVATE);if(prefs.getBoolean("removed_auto_elements_v1",false))return
        val db=ElementDatabase.get(context);db.withTransaction{val dao=db.elements();dao.purgeAutoDependencies();dao.purgeAutoBlobRefs();dao.purgeAutoRevisions();dao.purgeAutoElements()}
        // Old auto-generated manifests used lowercase library/elements. The
        // final project-created Ulelement location is uppercase Library/Element.
        File(AppFileSystem(context).library,"elements").deleteRecursively()
        prefs.edit().putBoolean("removed_auto_elements_v1",true).apply()
    }
}
