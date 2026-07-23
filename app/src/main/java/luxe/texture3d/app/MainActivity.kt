package luxe.texture3d.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.util.TypedValue
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowCompat
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private data class Template(val title:String,val asset:String?,val icon:Int)
    private val templates=listOf(
        Template("Empty scene",null,R.drawable.thumb_empty),Template("Cube","cube.glb",R.drawable.thumb_cube),
        Template("Sphere","sphere.glb",R.drawable.thumb_sphere),Template("Cylinder","cylinder.glb",R.drawable.thumb_cylinder),
        Template("Capsule","capsule.glb",R.drawable.thumb_capsule),Template("Plane","plane.glb",R.drawable.thumb_plane),
        Template("Round Box","round_box.glb",R.drawable.thumb_round_box),Template("Torus","torus.glb",R.drawable.thumb_torus),
        Template("Trolls","trolls.glb",R.drawable.thumb_trolls))
    private val prefs by lazy{getSharedPreferences("project_hub",MODE_PRIVATE)}
    // Reference-resolution scaler for the fixed landscape dashboard. Using
    // raw window pixels avoids vendor DPI differences making the same 1600×720
    // canvas appear radically larger on high-density phones.
    private val uiScale:Float by lazy {
        val dm=resources.displayMetrics
        min(dm.widthPixels/1600f,dm.heightPixels/720f).coerceIn(0.55f,1.60f)
    }
    private lateinit var content:FrameLayout
    private val nav=mutableListOf<TextView>()
    private var activePage=0

    private val chooseFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)};prefs.edit().putString(KEY_ROOT_URI,uri.toString()).apply();showProjects()}
    }

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);WindowCompat.setDecorFitsSystemWindows(window,false);window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT;window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);setContentView(buildShell());applyImmersiveMode();showHome();if(rootUri()==null)toast("Choose a folder before creating your first project")}
    override fun onResume(){super.onResume();applyImmersiveMode();if(::content.isInitialized){if(activePage==0)showHome() else if(activePage==1)showProjects()}}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)applyImmersiveMode()}

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode(){
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if(android.os.Build.VERSION.SDK_INT>=28)window.attributes=window.attributes.apply{layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
    }

    private fun buildShell():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(0xff020611.toInt())}
        root.addView(buildSidebar(),LinearLayout.LayoutParams(dp(74),-1))
        content=FrameLayout(this).apply{setBackgroundColor(0xff030714.toInt())}
        root.addView(content,LinearLayout.LayoutParams(0,-1,1f));return root
    }

    private fun buildSidebar():View{
        val side=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(7),dp(8),dp(7),dp(8));setBackgroundColor(0xff111111.toInt())}
        side.addView(ImageView(this).apply{setImageResource(R.drawable.luxe_icon);scaleType=ImageView.ScaleType.CENTER_INSIDE;tooltipText="Luxe Texture3D"},LinearLayout.LayoutParams(dp(52),dp(52)))
        val items=listOf("⌂" to "Home","▣" to "Projects","▤" to "Market","⌘" to "Plugin","▱" to "Draft","♙" to "Teams","⚙" to "Settings")
        items.forEachIndexed{i,(icon,title)->
            val b=label(icon,17f,0xffb6b6b6.toInt()).apply{gravity=Gravity.CENTER;setBackgroundResource(R.drawable.hub_nav_normal);tooltipText=title;contentDescription=title;setOnClickListener{selectPage(i)}}
            nav+=b;side.addView(b,LinearLayout.LayoutParams(dp(54),dp(54)).apply{topMargin=dp(5)})
        }
        side.addView(Space(this),LinearLayout.LayoutParams(1,0,1f))
        side.addView(label("●",11f,0xff43b96b.toInt()).apply{gravity=Gravity.CENTER;tooltipText="Ready"},LinearLayout.LayoutParams(dp(42),dp(32)))
        return side
    }

    private fun selectPage(index:Int){activePage=index;nav.forEachIndexed{i,v->v.setBackgroundResource(if(i==index)R.drawable.hub_nav_selected else R.drawable.hub_nav_normal)};when(index){0->showHome();1->showProjects();else->showComingSoon(index)}}
    private fun highlight(index:Int){activePage=index;nav.forEachIndexed{i,v->v.setBackgroundResource(if(i==index)R.drawable.hub_nav_selected else R.drawable.hub_nav_normal)}}

    private fun showHome(){showLibrary(0)}

    private fun showLibrary(navIndex:Int){
        if(!::content.isInitialized)return
        highlight(navIndex);content.removeAllViews()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),0);setBackgroundColor(0xff121212.toInt())}
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(0xff181818.toInt())}
        listOf("LIBRARY","TEMPLATES","IMPORT QUEUE").forEachIndexed{i,name->tabs.addView(label(name,11f,if(i==0)0xffe2e2e2.toInt() else 0xff777777.toInt()).apply{gravity=Gravity.CENTER;setBackgroundColor(if(i==0)0xff292929.toInt() else Color.TRANSPARENT);setOnClickListener{if(i!=0)toast("$name — Coming soon")}},LinearLayout.LayoutParams(dp(if(i==0)94 else 118),dp(40)))}
        tabs.addView(Space(this),LinearLayout.LayoutParams(0,1,1f));tabs.addView(action("＋  New Project",true){showNewProjectDialog()},LinearLayout.LayoutParams(dp(132),dp(36)))
        root.addView(tabs,LinearLayout.LayoutParams(-1,dp(42)))

        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(8))}
        tools.addView(action("＋  Create",false){showNewProjectDialog()},LinearLayout.LayoutParams(dp(96),dp(34)))
        tools.addView(action("▣  Folder",false){chooseFolder.launch(rootUri())},LinearLayout.LayoutParams(dp(94),dp(34)).apply{leftMargin=dp(5)})
        tools.addView(action("↻  Scan",false){showLibrary(navIndex)},LinearLayout.LayoutParams(dp(82),dp(34)).apply{leftMargin=dp(5)})
        val search=EditText(this).apply{hint="Search projects";setTextSize(TypedValue.COMPLEX_UNIT_PX,14f*uiScale);setTextColor(0xffdddddd.toInt());setHintTextColor(0xff666666.toInt());setSingleLine(true);background=getDrawable(R.drawable.hub_field_bg);setPadding(dp(10),0,dp(10),0)}
        tools.addView(search,LinearLayout.LayoutParams(0,dp(34),1f).apply{leftMargin=dp(8)})
        tools.addView(action("Sort: Last Edited  ▾",false){},LinearLayout.LayoutParams(dp(150),dp(34)).apply{leftMargin=dp(6)})
        root.addView(tools,LinearLayout.LayoutParams(-1,dp(50)))

        val heading=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heading.addView(label("MY PROJECTS",14f,0xffe0e0e0.toInt()).apply{setTypeface(typeface,1)},LinearLayout.LayoutParams(-2,-1))
        heading.addView(View(this).apply{setBackgroundColor(0xff383838.toInt())},LinearLayout.LayoutParams(0,1,1f).apply{leftMargin=dp(14)})
        root.addView(heading,LinearLayout.LayoutParams(-1,dp(42)))

        val dirs=projectDirs();val grid=GridLayout(this).apply{columnCount=if(resources.displayMetrics.widthPixels>=1400)5 else 4;alignmentMode=GridLayout.ALIGN_BOUNDS;useDefaultMargins=false}
        if(dirs.isEmpty())grid.addView(label("No projects. Select Create to begin.",13f,0xff777777.toInt()).apply{gravity=Gravity.CENTER},GridLayout.LayoutParams().apply{width=dp(420);height=dp(120)})
        else dirs.forEach{grid.addView(workstationProjectCard(it),GridLayout.LayoutParams().apply{width=dp(196);height=dp(190);setMargins(0,0,dp(12),dp(12))})}
        root.addView(ScrollView(this).apply{addView(grid)},LinearLayout.LayoutParams(-1,0,1f))

        val total=dirs.sumOf{it.findFile("model.glb")?.length()?:0L}
        root.addView(label("Ready   •   ${dirs.size} Projects   •   ${formatBytes(total)} Used   •   Luxe v0.14.0",10f,0xff777777.toInt()).apply{setPadding(dp(8),0,0,0);setBackgroundColor(0xff181818.toInt())},LinearLayout.LayoutParams(-1,dp(28)))
        content.addView(root,FrameLayout.LayoutParams(-1,-1))
    }

    private fun workstationProjectCard(dir:DocumentFile):View{
        val model=dir.findFile("model.glb");val modified=if(dir.lastModified()>0)relativeTime(dir.lastModified()) else "Unknown date"
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(7),dp(7),dp(7));setBackgroundResource(R.drawable.hub_card_bg);setOnClickListener{openProject(dir)}
            addView(ImageView(this@MainActivity).apply{loadProjectThumbnail(this,dir);setBackgroundColor(0xff242424.toInt())},LinearLayout.LayoutParams(-1,0,1f))
            addView(label(dir.name?:"Untitled",12f,0xffdedede.toInt()).apply{setTypeface(typeface,1)},LinearLayout.LayoutParams(-1,dp(25)))
            addView(label("$modified  •  ${formatBytes(model?.length()?:0L)}  •  v1",9f,0xff7e7e7e.toInt()),LinearLayout.LayoutParams(-1,dp(20)))
        }
    }

    private fun relativeTime(time:Long):String{val d=(System.currentTimeMillis()-time).coerceAtLeast(0);return when{d<3_600_000->"Modified ${d/60_000}m ago";d<86_400_000->"Modified ${d/3_600_000}h ago";else->"Modified ${d/86_400_000}d ago"}}
    private fun formatBytes(bytes:Long):String=when{bytes>=1024L*1024L->"%.1fMB".format(bytes/(1024f*1024f));bytes>=1024->"%.0fKB".format(bytes/1024f);else->"${bytes}B"}

    private fun hero():View{val frame=FrameLayout(this).apply{setBackgroundResource(R.drawable.hub_panel_bg)};frame.addView(ImageView(this).apply{setImageResource(R.drawable.header_bar);scaleType=ImageView.ScaleType.CENTER_CROP;alpha=0.92f},FrameLayout.LayoutParams(-1,-1));val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(17),0,0);addView(label("WELCOME",20f,Color.WHITE).apply{setTypeface(typeface,1)});addView(label("Smart 👋",26f,0xff1685ff.toInt()).apply{setTypeface(typeface,1)});addView(Space(this@MainActivity),LinearLayout.LayoutParams(1,dp(48)));addView(label("Bring Your Textures",20f,Color.WHITE).apply{setTypeface(typeface,1)});addView(label("To Life.",22f,0xffb45cff.toInt()).apply{setTypeface(typeface,1)})};frame.addView(words,FrameLayout.LayoutParams(dp(300),-1,Gravity.START));val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;addView(action("＋  New Project",true){showNewProjectDialog()},LinearLayout.LayoutParams(dp(160),dp(42)));addView(action("▣  Open Project",false){selectPage(1)},LinearLayout.LayoutParams(dp(150),dp(42)).apply{leftMargin=dp(8)})};frame.addView(actions,FrameLayout.LayoutParams(-2,dp(42),Gravity.START or Gravity.BOTTOM).apply{leftMargin=dp(22);bottomMargin=dp(16)});return frame}

    private fun recentProjects():View{val h=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};val dirs=projectDirs();val images=intArrayOf(R.drawable.engine_room,R.drawable.miltaryx,R.drawable.hk47,R.drawable.kingdom);if(dirs.isEmpty())listOf("Sci-Fi Hangar","Military Soldier","Assault Rifle","Abandoned City").forEachIndexed{i,n->row.addView(imageCard(n,"Edited recently",images[i]){showNewProjectDialog()},LinearLayout.LayoutParams(dp(190),dp(160)).apply{rightMargin=dp(9)})}else dirs.take(8).forEachIndexed{i,d->row.addView(imageCard(d.name?:"Project","Local project",images[i%images.size]){openProject(d)},LinearLayout.LayoutParams(dp(190),dp(160)).apply{rightMargin=dp(9)})};h.addView(row);return h}
    private fun marketplace():View{val h=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};listOf(Triple("Sci-Fi Pack","$34.99",R.drawable.scfi_building),Triple("Military Pack","$29.99",R.drawable.miltary),Triple("Industrial Pack","$24.99",R.drawable.engine_room),Triple("Nature Pack","$19.99",R.drawable.forest),Triple("City Pack","$39.99",R.drawable.city)).forEach{(n,p,img)->row.addView(imageCard(n,p,img){toast("Marketplace — Coming soon")},LinearLayout.LayoutParams(dp(160),dp(180)).apply{rightMargin=dp(9)})};h.addView(row);return h}

    private fun imageCard(title:String,sub:String,image:Int,open:()->Unit):View{val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.hub_card_bg);setPadding(dp(5),dp(5),dp(5),dp(7));setOnClickListener{open()}};card.addView(ImageView(this).apply{setImageResource(image);scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(-1,0,1f));card.addView(label(title,12f,Color.WHITE).apply{setPadding(dp(5),dp(5),0,0)},LinearLayout.LayoutParams(-1,dp(28)));card.addView(label(sub,10f,0xff8d9ab1.toInt()).apply{setPadding(dp(5),0,0,0)},LinearLayout.LayoutParams(-1,dp(20)));return card}
    private fun sectionTitle(left:String,right:String,click:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(label(left,14f,Color.WHITE).apply{setTypeface(typeface,1)},LinearLayout.LayoutParams(0,-1,1f));addView(label(right,11f,0xff2387ff.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{click()}},LinearLayout.LayoutParams(dp(70),-1))}

    private fun rightDashboard():View{val scroll=ScrollView(this);val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};col.addView(label("Quick Actions",13f,Color.WHITE).apply{setTypeface(typeface,1);setPadding(dp(10),0,0,0)},LinearLayout.LayoutParams(-1,dp(38)));listOf("New Texture Set" to "Start a new texture","Import 3D Model" to "Import FBX / GLB / GLTF","Material Library" to "Browse materials","Scan Device" to "Import from device").forEach{(a,b)->col.addView(quickRow(a,b),LinearLayout.LayoutParams(-1,dp(58)).apply{bottomMargin=dp(5)})};col.addView(sidePanel("News & Updates",listOf("Luxe 2.0 is Here","Marketplace Sale","AI Upscaler Released")),LinearLayout.LayoutParams(-1,dp(190)).apply{topMargin=dp(8)});col.addView(sidePanel("Installed Plugins",listOf("Texture Baker   v1.2.0 ✓","AI Upscaler   v1.0.3 ✓","Material Converter   v2.1.0 ✓","GLTF Exporter   v1.3.2 ✓")),LinearLayout.LayoutParams(-1,dp(185)).apply{topMargin=dp(8)});scroll.addView(col);return scroll}
    private fun quickRow(a:String,b:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0);setBackgroundResource(R.drawable.hub_card_bg);addView(label("›  $a",11f,Color.WHITE));addView(label("    $b",9f,0xff73829a.toInt()))}
    private fun sidePanel(title:String,items:List<String>)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(9),dp(12),dp(8));setBackgroundResource(R.drawable.hub_panel_bg);addView(label(title,12f,Color.WHITE).apply{setTypeface(typeface,1)},LinearLayout.LayoutParams(-1,dp(27)));items.forEach{addView(label("◉  $it",10f,0xffc9d4e4.toInt()),LinearLayout.LayoutParams(-1,dp(34)))}}

    private fun showProjects(){showLibrary(1)}
    private fun projectRow(dir:DocumentFile)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(10),dp(6));setBackgroundResource(R.drawable.hub_project_row);addView(ImageView(this@MainActivity).apply{loadProjectThumbnail(this,dir)},LinearLayout.LayoutParams(dp(52),dp(52)));addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;addView(label(dir.name?:"Untitled",13f,Color.WHITE).apply{setTypeface(typeface,1)});addView(label("${displayFolderName()} / ${dir.name}",10f,0xff728099.toInt()))},LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(10)});addView(label("⋮",22f,0xff8c97aa.toInt()).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(42),-1));addView(action("Open",false){openProject(dir)},LinearLayout.LayoutParams(dp(80),dp(34)))}
    private fun showComingSoon(index:Int){highlight(index);content.removeAllViews();content.addView(label(listOf("","","Marketplace","Plugin Manager","Drafts","Teams","Settings").getOrElse(index){"Coming Soon"},24f,0xff4f8fff.toInt()).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))}

    private fun showNewProjectDialog(){if(rootDocument()==null){toast("Choose a project folder first");chooseFolder.launch(null);return};val d=Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);val p=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(16),dp(22),dp(16));setBackgroundResource(R.drawable.hub_dialog_bg)};val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(label("Create New Project",16f,Color.WHITE),LinearLayout.LayoutParams(0,dp(34),1f));addView(label("×",24f,0xffb9c2d0.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{d.dismiss()}},LinearLayout.LayoutParams(dp(34),dp(34)))};p.addView(head);p.addView(label("Project Name",11f,0xff9aa7b9.toInt()),LinearLayout.LayoutParams(-1,dp(27)));val name=EditText(this).apply{hint="My Texture Project";setTextSize(TypedValue.COMPLEX_UNIT_PX,16f*uiScale);setTextColor(Color.WHITE);setHintTextColor(0xff58657a.toInt());setSingleLine(true);background=getDrawable(R.drawable.hub_field_bg)};p.addView(name,LinearLayout.LayoutParams(-1,dp(42)));val err=label("",10f,0xffffa24a.toInt()).apply{visibility=View.GONE};p.addView(err,LinearLayout.LayoutParams(-1,dp(24)));p.addView(label("Template",11f,0xff9aa7b9.toInt()),LinearLayout.LayoutParams(-1,dp(27)));val tr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4));setBackgroundResource(R.drawable.hub_field_bg)};val preview=ImageView(this).apply{setImageResource(templates[0].icon);setColorFilter(0xffbae6fd.toInt())};val spinner=Spinner(this).apply{adapter=templateAdapter()};spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){preview.setImageResource(templates[pos].icon)};override fun onNothingSelected(p:AdapterView<*>?){}};tr.addView(preview,LinearLayout.LayoutParams(dp(52),dp(52)));tr.addView(spinner,LinearLayout.LayoutParams(0,dp(48),1f));p.addView(tr,LinearLayout.LayoutParams(-1,dp(62)));p.addView(label("Project folder: ${displayFolderName()}",10f,0xff66738a.toInt()),LinearLayout.LayoutParams(-1,dp(34)));val acts=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END};acts.addView(action("Cancel",false){d.dismiss()},LinearLayout.LayoutParams(dp(90),dp(38)));acts.addView(action("Create & Open",true){val t=name.text.toString().trim();if(t.isBlank()){err.text="Project name is required";err.visibility=View.VISIBLE}else createProject(t,templates[spinner.selectedItemPosition])?.let{d.dismiss();openProject(it)}},LinearLayout.LayoutParams(dp(132),dp(38)).apply{leftMargin=dp(8)});p.addView(acts,LinearLayout.LayoutParams(-1,dp(46)));d.setContentView(p);d.show();d.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply{dimAmount=.78f};setLayout((resources.displayMetrics.widthPixels*.60f).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT)}}

    private fun createProject(title:String,t:Template):DocumentFile?{val root=rootDocument()?:return null;val safe=title.replace(Regex("[\\\\/:*?\"<>|]"),"_").trim().take(64);if(safe.isBlank()||root.findFile(safe)!=null){toast("Invalid or duplicate project name");return null};return runCatching{val f=root.createDirectory(safe)?:error("Could not create folder");val meta="{\"name\":\"$safe\",\"template\":\"${t.title}\",\"version\":1,\"thumbnail\":\"thumbnail.png\",\"renderCamera\":null}";f.createFile("application/json","project.json")?.let{contentResolver.openOutputStream(it.uri)?.use{o->o.write(meta.toByteArray())}};t.asset?.let{a->val out=f.createFile("model/gltf-binary","model.glb")?:error("Could not create model");assets.open("templates/$a").use{i->contentResolver.openOutputStream(out.uri)?.use{i.copyTo(it)}?:error("Could not write model")}};f}.onFailure{toast(it.message?:"Project creation failed")}.getOrNull()}
    private fun loadProjectThumbnail(view:ImageView,folder:DocumentFile){
        val thumbnail=folder.findFile("thumbnail.png")
        val bitmap=thumbnail?.let{runCatching{contentResolver.openInputStream(it.uri)?.use(BitmapFactory::decodeStream)}.getOrNull()}
        if(bitmap!=null){view.setImageBitmap(bitmap);view.scaleType=ImageView.ScaleType.CENTER_CROP;view.setPadding(0,0,0,0);view.clearColorFilter()}
        else{view.setImageResource(R.drawable.luxe_launcher);view.scaleType=ImageView.ScaleType.CENTER_INSIDE;view.setPadding(dp(22),dp(14),dp(22),dp(14));view.clearColorFilter()}
    }

    private fun projectIcon(f:DocumentFile):Int{val m=f.findFile("project.json")?.let{runCatching{contentResolver.openInputStream(it.uri)?.bufferedReader()?.use{r->r.readText()}.orEmpty()}.getOrDefault("")}.orEmpty().lowercase();return when{"sphere" in m->R.drawable.thumb_sphere;"cylinder" in m->R.drawable.thumb_cylinder;"capsule" in m->R.drawable.thumb_capsule;"plane" in m->R.drawable.thumb_plane;"round box" in m->R.drawable.thumb_round_box;"torus" in m->R.drawable.thumb_torus;"trolls" in m->R.drawable.thumb_trolls;"cube" in m->R.drawable.thumb_cube;else->R.drawable.thumb_empty}}
    private fun projectDirs()=rootDocument()?.let{runCatching{it.listFiles().filter{f->f.isDirectory}.sortedBy{f->f.name?.lowercase()}}.getOrDefault(emptyList())}?:emptyList()
    private fun openProject(f:DocumentFile){startActivity(Intent(this,EditorActivity::class.java).apply{f.findFile("model.glb")?.let{putExtra(EditorActivity.EXTRA_PROJECT_MODEL_URI,it.uri.toString())}})}
    private fun rootUri():Uri?=prefs.getString(KEY_ROOT_URI,null)?.let(Uri::parse);private fun rootDocument():DocumentFile?=rootUri()?.let{DocumentFile.fromTreeUri(this,it)};private fun displayFolderName()=rootDocument()?.name?:"No folder selected"
    private fun templateAdapter()=object:BaseAdapter(){
        override fun getCount()=templates.size
        override fun getItem(position:Int)=templates[position].title
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup)=spinnerText(templates[position].title)
        override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=spinnerText(templates[position].title).apply{setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(0xff1e2433.toInt())}
    }
    private fun spinnerText(value:String)=label(value,12f,Color.WHITE).apply{setPadding(dp(10),0,dp(10),0)}
    private fun label(t:String,s:Float,c:Int)=TextView(this).apply{text=t;setTextSize(TypedValue.COMPLEX_UNIT_PX,s*1.25f*uiScale);setTextColor(c);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL}
    private fun action(t:String,primary:Boolean,go:()->Unit)=TextView(this).apply{text=t;setTextSize(TypedValue.COMPLEX_UNIT_PX,15f*uiScale);gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(if(primary)R.drawable.hub_primary_button else R.drawable.hub_secondary_button);setOnClickListener{go()}}
    private fun toast(t:String)=Toast.makeText(this,t,Toast.LENGTH_LONG).show();private fun dp(v:Int)=(v*uiScale).roundToInt()
    companion object{private const val KEY_ROOT_URI="project_root_uri"}
}
