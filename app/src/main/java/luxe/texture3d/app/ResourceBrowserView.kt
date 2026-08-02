package luxe.texture3d.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Reusable Hub/editor resource browser foundation. */
class ResourceBrowserView(context:Context,private val onBack:()->Unit):LinearLayout(context){
    data class Item(val id:String,val name:String,val type:String,val subtitle:String="",val thumbnail:File?=null,val modified:Long=0,val source:File?=null)
    private val fs=AppFileSystem(context);private val scale=min(resources.displayMetrics.widthPixels/1600f,resources.displayMetrics.heightPixels/720f).coerceIn(.55f,1.6f)
    private val tree=LinearLayout(context);private val content=LinearLayout(context);private val query=EditText(context);private val cache=object:LruCache<String,Bitmap>(12*1024){override fun sizeOf(k:String,v:Bitmap)=v.byteCount/1024}
    private var allItems:List<Item> = emptyList();private var gridMode=true;private var sortMode="Name";private var currentTitle="Projects"

    init{orientation=VERTICAL;setBackgroundColor(0xff121212.toInt());addView(topBar(),LayoutParams(-1,u(48)));val body=LinearLayout(context).apply{orientation=HORIZONTAL};tree.orientation=VERTICAL;tree.setPadding(u(5),u(6),u(5),u(6));tree.setBackgroundColor(0xff171717.toInt());body.addView(ScrollView(context).apply{addView(tree)},LayoutParams(u(220),-1));content.orientation=VERTICAL;content.setPadding(u(10),u(8),u(10),u(8));body.addView(content,LayoutParams(0,-1,1f));addView(body,LayoutParams(-1,0,1f));buildTree();showProjects()}

    private fun topBar():View{val row=LinearLayout(context).apply{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(u(9),u(5),u(9),u(5));setBackgroundColor(0xff1b1b1b.toInt())};row.addView(button("‹  Back"){onBack()},LayoutParams(u(88),u(36)));row.addView(text("RESOURCE BROWSER",16f,Color.WHITE).apply{setTypeface(typeface,1);setPadding(u(12),0,0,0)},LayoutParams(u(210),-1));query.hint="Search library";query.setSingleLine(true);query.setTextColor(Color.WHITE);query.setHintTextColor(0xff666666.toInt());query.setTextSize(TypedValue.COMPLEX_UNIT_PX,13f*1.25f*scale);query.background=context.getDrawable(R.drawable.hub_field_bg);query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int)=render();override fun afterTextChanged(s:Editable?) {}});row.addView(query,LayoutParams(0,u(36),1f));val sort=Spinner(context);sort.adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,listOf("Name","Recently Modified","Type"));sort.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){sortMode=listOf("Name","Recently Modified","Type")[pos];render()};override fun onNothingSelected(p:AdapterView<*>?){}};row.addView(sort,LayoutParams(u(150),u(36)).apply{leftMargin=u(6)});row.addView(button("▦ / ☰"){gridMode=!gridMode;render()},LayoutParams(u(72),u(36)).apply{leftMargin=u(6)});return row}

    private fun buildTree(){tree.removeAllViews();treeHeader("LIBRARY");treeRow("▣","Projects",0){showProjects()};treeRow("◇","Assets",0){showAssets()};treeRow("▤","Library",0){showLibraryRoot()};treeRow("⌘","Elements",0){showElements()};treeRow("◷","Recent",0){showRecent()};treeRow("♲","Trash",0){showTrash()};treeHeader("TYPED LIBRARY");listOf("Texture","Audio","Video","Environment","Animation","Script").forEach{category->treeRow("·",category,1){showLibraryCategory(category)}};treeHeader("ASSET FAMILIES");fs.assets.listFiles()?.filter{it.isDirectory&&File(it,"family.json").isFile}?.sortedBy{it.name.lowercase()}?.take(100)?.forEach{asset->treeRow("›",friendlyAssetName(asset),1){expandAsset(asset)}}}
    private fun expandAsset(asset:File){currentTitle=friendlyAssetName(asset);val family=runCatching{JSONObject(File(asset,"family.json").readText())}.getOrNull()?:return;val list=mutableListOf<Item>();list+=familyItems(asset,family,"geometry","Geometry");list+=familyItems(asset,family,"materials","Material");list+=familyItems(asset,family,"textures","Texture");list+=familyItems(asset,family,"animations","Animation");list+=familyItems(asset,family,"cameras","Camera");list+=familyItems(asset,family,"lights","Light");setItems(list)}

    private fun familyItems(asset:File,family:JSONObject,key:String,type:String):List<Item>{val array=family.optJSONArray(key)?:return emptyList();return (0 until array.length()).mapNotNull{i->val value=array.optJSONObject(i)?:return@mapNotNull null;val name=value.optString("name").ifBlank{"$type ${i+1}"};val detail=when(type){"Geometry"->"${value.optLong("triangleCount")} tris • ${value.optLong("vertexCount")} vertices";"Texture"->value.optString("usage","unknown");else->"${type} index ${value.optInt(type.lowercase()+"Index",value.optInt("index",i))}"};val descriptor=value.optString("libraryPath").takeIf{it.isNotBlank()}?.let{File(fs.library,it)};Item("${asset.name}:$key:$i",name,type,detail,descriptorThumbnail(descriptor),asset.lastModified(),descriptor)}}
    private fun showProjects(){currentTitle="Projects";setItems(fs.projects.listFiles()?.filter{it.isDirectory}?.map{dir->Item(dir.name,dir.name,"Project",relative(dir.lastModified()),File(dir,"thumbnail.png").takeIf{it.isFile},dir.lastModified(),dir)}?:emptyList())}
    private fun showAssets(){currentTitle="Assets";setItems(fs.assets.listFiles()?.filter{it.isDirectory&&File(it,"asset.json").isFile}?.map{dir->val meta=json(File(dir,"asset.json"));Item(dir.name,meta?.optString("displayName")?.substringBeforeLast('.')?.ifBlank{dir.name}?:dir.name,"Asset","${meta?.optLong("triangleCount",0)?:0} tris • ${meta?.optInt("textureCount",0)?:0} textures",File(dir,"thumbnail.png").takeIf{it.isFile},dir.lastModified(),dir)}?:emptyList())}
    private fun showLibraryRoot(){currentTitle="Library";setItems(listOf("Texture","Audio","Video","Environment","Animation","Script").map{category->val dir=libraryDir(category);Item(category,category,"Folder","${dir.listFiles()?.size?:0} items",null,dir.lastModified(),dir)})}
    private fun showLibraryCategory(category:String){currentTitle="Library / $category";val root=libraryDir(category);val ext=when(category){"Texture"->"texture";"Animation"->"anim";else->null};val files=root.walkTopDown().filter{it.isFile&&(ext==null||it.extension.equals(ext,true))}.take(500).map{file->val data=json(file);val name=data?.optString("name")?.ifBlank{file.nameWithoutExtension}?:file.nameWithoutExtension;val type=data?.optString("type")?.ifBlank{category}?:category;val payload=data?.optString("file")?.let{File(file.parentFile,it)};Item(file.absolutePath,name,type,data?.optString("usage").orEmpty(),payload?.takeIf{it.isFile},file.lastModified(),file)}.toList();setItems(files)}
    private fun showElements(){currentTitle="Elements";val items=fs.elements.walkTopDown().filter{it.isFile&&it.extension.equals("ulelement",true)}.map{file->val data=json(file);Item(file.absolutePath,data?.optString("name")?:file.nameWithoutExtension,"Ulelement","Family Element",File(file.parentFile,"thumbnail.png").takeIf{it.isFile},file.lastModified(),file)}.toList();setItems(items)}
    private fun showRecent(){currentTitle="Recent";setItems((assetItems()+projectItems()).sortedByDescending{it.modified}.take(100))}
    private fun showTrash(){currentTitle="Trash";setItems(fs.libraryTrash.listFiles()?.map{Item(it.absolutePath,it.name,"Trash","",null,it.lastModified(),it)}?:emptyList())}
    private fun assetItems():List<Item>{showAssets();return allItems}
    private fun projectItems():List<Item>{showProjects();return allItems}

    private fun setItems(items:List<Item>){allItems=items;render()}
    private fun render(){content.removeAllViews();content.addView(text("$currentTitle   •   ${filtered().size} items",13f,0xffdddddd.toInt()).apply{setTypeface(typeface,1)},LayoutParams(-1,u(34)));val holder=if(gridMode)grid(filtered())else list(filtered());content.addView(ScrollView(context).apply{addView(holder)},LayoutParams(-1,0,1f))}
    private fun filtered():List<Item>{val q=query.text.toString().trim().lowercase();val source=if(q.isBlank())allItems else allItems.filter{it.name.lowercase().contains(q)||it.type.lowercase().contains(q)||it.subtitle.lowercase().contains(q)};return when(sortMode){"Recently Modified"->source.sortedByDescending{it.modified};"Type"->source.sortedWith(compareBy<Item>{it.type}.thenBy{it.name});else->source.sortedBy{it.name.lowercase()}}}
    private fun grid(items:List<Item>):View{val grid=GridLayout(context).apply{columnCount=if(resources.displayMetrics.widthPixels>=1400)5 else 4};items.forEach{grid.addView(card(it),GridLayout.LayoutParams().apply{width=u(190);height=u(180);setMargins(0,0,u(9),u(9))})};return grid}
    private fun list(items:List<Item>):View=LinearLayout(context).apply{orientation=VERTICAL;items.forEach{addView(listRow(it),LayoutParams(-1,u(62)).apply{bottomMargin=u(4)})}}
    private fun card(item:Item)=LinearLayout(context).apply{orientation=VERTICAL;setPadding(u(6),u(6),u(6),u(6));setBackgroundResource(R.drawable.hub_card_bg);addView(image(item),LayoutParams(-1,0,1f));addView(text(item.name,11f,0xffdddddd.toInt()).apply{setTypeface(typeface,1)},LayoutParams(-1,u(24)));addView(text("${item.type}  •  ${item.subtitle}",9f,0xff777777.toInt()),LayoutParams(-1,u(19)))}
    private fun listRow(item:Item)=LinearLayout(context).apply{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(u(8),u(5),u(8),u(5));setBackgroundResource(R.drawable.hub_project_row);addView(image(item),LayoutParams(u(48),u(48)));addView(LinearLayout(context).apply{orientation=VERTICAL;addView(text(item.name,12f,0xffdddddd.toInt()));addView(text("${item.type} • ${item.subtitle}",9f,0xff777777.toInt()))},LayoutParams(0,-1,1f).apply{leftMargin=u(9)})}
    private fun image(item:Item)=ImageView(context).apply{val bitmap=item.thumbnail?.let(::bitmap);if(bitmap!=null){setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP}else{setImageResource(if(item.type=="Project")R.drawable.luxe_launcher else R.drawable.thumb_empty);scaleType=ImageView.ScaleType.CENTER_INSIDE;setPadding(u(16),u(10),u(16),u(10))}}
    private fun bitmap(file:File):Bitmap?{if(!file.isFile)return null;cache.get(file.absolutePath)?.let{return it};val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,b);var sample=1;while(b.outWidth/sample>512||b.outHeight/sample>512)sample*=2;return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})?.also{cache.put(file.absolutePath,it)}}

    private fun treeHeader(value:String){tree.addView(text(value,9f,0xff666666.toInt()).apply{setPadding(u(8),u(8),0,0)},LayoutParams(-1,u(28)))}
    private fun treeRow(icon:String,title:String,depth:Int,click:()->Unit){tree.addView(text("$icon   $title",11f,0xffb8b8b8.toInt()).apply{setPadding(u(10+depth*14),0,u(5),0);setOnClickListener{click()}},LayoutParams(-1,u(36)))}
    private fun button(value:String,click:()->Unit)=text(value,11f,0xffdddddd.toInt()).apply{gravity=Gravity.CENTER;setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{click()}}
    private fun text(value:String,size:Float,color:Int)=TextView(context).apply{text=value;setTextSize(TypedValue.COMPLEX_UNIT_PX,size*1.25f*scale);setTextColor(color);gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false}
    private fun libraryDir(category:String)=when(category){"Texture"->fs.libraryTextures;"Audio"->fs.libraryAudio;"Video"->fs.libraryVideo;"Environment"->fs.libraryEnvironment;"Animation"->fs.libraryAnimation;"Script"->fs.libraryScript;else->fs.library}
    private fun descriptorThumbnail(file:File?):File?{if(file==null)return null;val data=json(file)?:return null;return data.optString("file").takeIf{it.isNotBlank()}?.let{File(file.parentFile,it)}?.takeIf{it.isFile}}
    private fun json(file:File)=runCatching{JSONObject(file.readText())}.getOrNull()
    private fun friendlyAssetName(dir:File)=json(File(dir,"asset.json"))?.optString("displayName")?.substringBeforeLast('.')?.ifBlank{dir.name}?:dir.name
    private fun relative(time:Long):String{val d=(System.currentTimeMillis()-time).coerceAtLeast(0);return if(d<86_400_000)"Modified ${d/3_600_000}h ago" else "Modified ${d/86_400_000}d ago"}
    private fun u(v:Int)=(v*scale).roundToInt()
}
