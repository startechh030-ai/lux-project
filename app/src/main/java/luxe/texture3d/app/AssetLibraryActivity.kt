package luxe.texture3d.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class AssetLibraryActivity:AppCompatActivity(){
    private val files by lazy{AppFileSystem(this)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(buildUi())}
    private fun buildUi():android.view.View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));setBackgroundColor(0xff121212.toInt())}
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(button("‹  Back"){finish()},LinearLayout.LayoutParams(dp(90),dp(38)));top.addView(TextView(this).apply{text="MY ASSETS";textSize=17f;setTextColor(0xffeeeeee.toInt());setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,0,0)},LinearLayout.LayoutParams(0,dp(42),1f));root.addView(top)
        val dirs=files.assets.listFiles()?.filter{it.isDirectory}?.sortedByDescending{it.lastModified()}?:emptyList();val grid=GridLayout(this).apply{columnCount=5;setPadding(0,dp(10),0,0)}
        dirs.forEach{dir->grid.addView(card(dir),GridLayout.LayoutParams().apply{width=dp(190);height=dp(180);setMargins(0,0,dp(10),dp(10))})}
        if(dirs.isEmpty())grid.addView(TextView(this).apply{text="No converted assets yet";textSize=14f;setTextColor(0xff777777.toInt());gravity=Gravity.CENTER},GridLayout.LayoutParams().apply{width=dp(500);height=dp(120)})
        root.addView(ScrollView(this).apply{addView(grid)},LinearLayout.LayoutParams(-1,0,1f));return root
    }
    private fun card(dir:File)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(7),dp(7),dp(7));setBackgroundResource(R.drawable.hub_card_bg);setOnClickListener{Toast.makeText(this@AssetLibraryActivity,"Editor glTF resource loading is the next connection step",Toast.LENGTH_SHORT).show()};addView(ImageView(this@AssetLibraryActivity).apply{val bitmap=BitmapFactory.decodeFile(File(dir,"thumbnail.png").absolutePath);if(bitmap!=null){setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP}else{setImageResource(R.drawable.luxe_launcher);scaleType=ImageView.ScaleType.CENTER_INSIDE}},LinearLayout.LayoutParams(-1,0,1f));addView(TextView(this@AssetLibraryActivity).apply{text=dir.name.substringBeforeLast('-');textSize=12f;setTextColor(0xffdddddd.toInt());setTypeface(typeface,1)},LinearLayout.LayoutParams(-1,dp(26)));addView(TextView(this@AssetLibraryActivity).apply{text="model.gltf";textSize=10f;setTextColor(0xff777777.toInt())},LinearLayout.LayoutParams(-1,dp(20)))}
    private fun button(t:String,go:()->Unit)=TextView(this).apply{text=t;textSize=12f;gravity=Gravity.CENTER;setTextColor(0xffeeeeee.toInt());setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{go()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object{const val EXTRA_ASSET_PATH="luxe.asset.path"}
}
