package luxe.texture3d.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AssetLibraryActivity:AppCompatActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(ResourceBrowserView(this){finish()})}
    companion object{const val EXTRA_ASSET_PATH="luxe.asset.path"}
}
