package luxe.texture3d.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private data class Template(val title:String,val asset:String?,val icon:Int)
    private val templates=listOf(
        Template("Empty scene",null,R.drawable.thumb_empty), Template("Cube","cube.glb",R.drawable.thumb_cube),
        Template("Sphere","sphere.glb",R.drawable.thumb_sphere), Template("Cylinder","cylinder.glb",R.drawable.thumb_cylinder),
        Template("Capsule","capsule.glb",R.drawable.thumb_capsule), Template("Plane","plane.glb",R.drawable.thumb_plane),
        Template("Round Box","round_box.glb",R.drawable.thumb_round_box), Template("Torus","torus.glb",R.drawable.thumb_torus),
        Template("Trolls","trolls.glb",R.drawable.thumb_trolls)
    )
    private lateinit var projectsList:LinearLayout
    private lateinit var folderText:TextView
    private val prefs by lazy{getSharedPreferences("project_hub",MODE_PRIVATE)}

    private val chooseFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){
            runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            prefs.edit().putString(KEY_ROOT_URI,uri.toString()).apply();refreshFolderLabel();refreshProjects()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xff111111.toInt();window.navigationBarColor=0xff111111.toInt()
        setContentView(buildUi());refreshFolderLabel();refreshProjects()
        if(rootUri()==null) showFolderDialog()
    }
    override fun onResume(){super.onResume();if(::projectsList.isInitialized)refreshProjects()}

    private fun buildUi():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff111111.toInt())}
        root.addView(buildTopBar(),LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(buildToolBar(),LinearLayout.LayoutParams(-1,dp(44)))
        folderText=text("",11f,0xff818181.toInt()).apply{setPadding(dp(14),dp(5),dp(14),dp(5))}
        root.addView(folderText,LinearLayout.LayoutParams(-1,dp(30)))
        projectsList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),dp(8))}
        root.addView(ScrollView(this).apply{setBackgroundColor(0xff161616.toInt());addView(projectsList)},LinearLayout.LayoutParams(-1,0,1f))
        return root
    }

    private fun buildTopBar():View{
        val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(10),0);setBackgroundColor(0xff171717.toInt())}
        bar.addView(ImageView(this).apply{setImageResource(R.drawable.ic_luxe_logo)},LinearLayout.LayoutParams(dp(30),dp(30)))
        bar.addView(text("LUXE TEXTURE3D",17f,0xffe4e4e4.toInt()).apply{setTypeface(typeface,1);setPadding(dp(7),0,dp(22),0)},LinearLayout.LayoutParams(-2,-1))
        listOf("Projects","Marketplace","Settings","Plugin Manager").forEachIndexed{i,label->
            bar.addView(text(label,13f,if(i==0)0xff58a6ff.toInt() else 0xffa0a0a0.toInt()).apply{
                gravity=Gravity.CENTER;setPadding(dp(13),0,dp(13),0)
                setOnClickListener{if(i!=0)Toast.makeText(this@MainActivity,"$label — Coming soon",Toast.LENGTH_SHORT).show()}
            },LinearLayout.LayoutParams(-2,-1))
        }
        bar.addView(Space(this),LinearLayout.LayoutParams(0,1,1f))
        bar.addView(text("⚙  Settings",12f,0xffb0b0b0.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{Toast.makeText(this@MainActivity,"Settings — Coming soon",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(dp(102),-1))
        return bar
    }

    private fun buildToolBar():View{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(9),dp(5),dp(9),dp(5));setBackgroundColor(0xff202020.toInt())}
        row.addView(smallButton("＋  Create",true){showNewProjectDialog()},LinearLayout.LayoutParams(-2,-1))
        row.addView(smallButton("▣  Folder",false){chooseFolder.launch(rootUri())},LinearLayout.LayoutParams(-2,-1).apply{leftMargin=dp(5)})
        row.addView(smallButton("↻  Scan",false){refreshProjects()},LinearLayout.LayoutParams(-2,-1).apply{leftMargin=dp(5)})
        val search=EditText(this).apply{hint="Filter projects";textSize=12f;setTextColor(0xffdddddd.toInt());setHintTextColor(0xff777777.toInt());setSingleLine(true);background=getDrawable(R.drawable.hub_field_bg);setPadding(dp(10),0,dp(10),0)}
        row.addView(search,LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(7)})
        row.addView(text("Sort:  Last Edited  ▾",12f,0xffb5b5b5.toInt()).apply{gravity=Gravity.CENTER;setBackgroundResource(R.drawable.hub_secondary_button)},LinearLayout.LayoutParams(dp(150),-1).apply{leftMargin=dp(7)})
        return row
    }

    private fun refreshFolderLabel(){folderText.text=rootDocument()?.name?.let{"Project folder  /  $it"}?:"No project folder selected"}
    private fun refreshProjects(){
        projectsList.removeAllViews();val root=rootDocument()
        if(root==null){projectsList.addView(emptyLabel("Choose a project folder to begin."));return}
        val dirs=runCatching{root.listFiles().filter{it.isDirectory}.sortedBy{it.name?.lowercase()}}.getOrDefault(emptyList())
        if(dirs.isEmpty()){projectsList.addView(emptyLabel("No local projects. Select Create to begin."));return}
        dirs.forEach{projectsList.addView(projectRow(it),LinearLayout.LayoutParams(-1,dp(70)))}
    }

    private fun projectRow(dir:DocumentFile):View{
        val model=dir.findFile("model.glb")
        return LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(7),dp(12),dp(7));setBackgroundResource(R.drawable.hub_project_row)
            addView(ImageView(this@MainActivity).apply{setImageResource(projectIcon(dir));setColorFilter(0xffbae6fd.toInt());setPadding(dp(5),dp(5),dp(5),dp(5))},LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(LinearLayout(this@MainActivity).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
                addView(text(dir.name?:"Untitled",14f,0xffeeeeee.toInt()).apply{setTypeface(typeface,1)})
                addView(text(if(model!=null)"${displayFolderName()} / ${dir.name}" else "Empty scene project",11f,0xff858585.toInt()))
            },LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(10)})
            addView(text("⋮",23f,0xffa0a0a0.toInt()).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(42),-1))
            addView(smallButton("Open",false){openProject(dir)},LinearLayout.LayoutParams(dp(82),dp(34)))
        }
    }

    private fun showNewProjectDialog(){
        if(rootDocument()==null){showFolderDialog();return}
        val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(16),dp(22),dp(16));setBackgroundResource(R.drawable.hub_dialog_bg)}
        val heading=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heading.addView(text("Create New Project",16f,0xffeeeeee.toInt()),LinearLayout.LayoutParams(0,dp(36),1f))
        heading.addView(text("×",24f,0xffc0c0c0.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(dp(36),dp(36)))
        panel.addView(heading)
        panel.addView(text("Project Name",11f,0xffaaaaaa.toInt()).apply{setPadding(0,dp(8),0,dp(5))})
        val name=EditText(this).apply{hint="My Texture Project";textSize=13f;setTextColor(0xffeeeeee.toInt());setHintTextColor(0xff666666.toInt());setSingleLine(true);background=getDrawable(R.drawable.hub_field_bg)}
        panel.addView(name,LinearLayout.LayoutParams(-1,dp(43)))
        val error=text("",11f,0xffffa24a.toInt()).apply{visibility=View.GONE;setPadding(dp(2),dp(4),0,0)}
        panel.addView(error,LinearLayout.LayoutParams(-1,dp(26)))
        panel.addView(text("Template",11f,0xffaaaaaa.toInt()).apply{setPadding(0,dp(5),0,dp(5))})
        val templateRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(5),dp(10),dp(5));setBackgroundResource(R.drawable.hub_field_bg)}
        val preview=ImageView(this).apply{setImageResource(templates[0].icon);setColorFilter(0xffbae6fd.toInt());setPadding(dp(5),dp(5),dp(5),dp(5))}
        val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,templates.map{it.title})}
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){preview.setImageResource(templates[pos].icon)}
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
        templateRow.addView(preview,LinearLayout.LayoutParams(dp(56),dp(56)))
        templateRow.addView(spinner,LinearLayout.LayoutParams(0,dp(48),1f))
        panel.addView(templateRow,LinearLayout.LayoutParams(-1,dp(66)))
        panel.addView(text("Project folder: ${displayFolderName()}",10f,0xff777777.toInt()).apply{setPadding(0,dp(9),0,dp(8))})
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END}
        actions.addView(smallButton("Cancel",false){dialog.dismiss()},LinearLayout.LayoutParams(dp(92),dp(38)))
        actions.addView(smallButton("Create & Open",true){
            val title=name.text.toString().trim()
            if(title.isBlank()){error.text="Project name is required";error.visibility=View.VISIBLE;return@smallButton}
            createProject(title,templates[spinner.selectedItemPosition])?.let{dialog.dismiss();openProject(it)}
        },LinearLayout.LayoutParams(dp(132),dp(38)).apply{leftMargin=dp(8)})
        panel.addView(actions,LinearLayout.LayoutParams(-1,dp(46)))
        dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(false);dialog.show()
        dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply{dimAmount=0.72f};setLayout((resources.displayMetrics.widthPixels*0.62f).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT)}
    }

    private fun createProject(title:String,template:Template):DocumentFile?{
        val root=rootDocument()?:return null;val safe=title.replace(Regex("[\\\\/:*?\"<>|]"),"_").trim().take(64)
        if(safe.isBlank()){toast("Invalid project name");return null};if(root.findFile(safe)!=null){toast("A project with this name already exists");return null}
        return runCatching{
            val folder=root.createDirectory(safe)?:error("Could not create project folder")
            val escaped=safe.replace("\"","\\\"");val metadata="{\"name\":\"$escaped\",\"template\":\"${template.title}\",\"version\":1}"
            folder.createFile("application/json","project.json")?.let{contentResolver.openOutputStream(it.uri)?.use{out->out.write(metadata.toByteArray())}}
            template.asset?.let{assetName->val out=folder.createFile("model/gltf-binary","model.glb")?:error("Could not create model file");assets.open("templates/$assetName").use{input->contentResolver.openOutputStream(out.uri)?.use{input.copyTo(it)}?:error("Could not write template")}}
            folder
        }.onFailure{toast(it.message?:"Project creation failed")}.getOrNull()
    }

    private fun projectIcon(folder:DocumentFile):Int{
        val m=folder.findFile("project.json")?.let{runCatching{contentResolver.openInputStream(it.uri)?.bufferedReader()?.use{r->r.readText()}.orEmpty()}.getOrDefault("")}.orEmpty().lowercase()
        return when{"sphere" in m->R.drawable.thumb_sphere;"cylinder" in m->R.drawable.thumb_cylinder;"capsule" in m->R.drawable.thumb_capsule;"plane" in m->R.drawable.thumb_plane;"round box" in m->R.drawable.thumb_round_box;"torus" in m->R.drawable.thumb_torus;"trolls" in m->R.drawable.thumb_trolls;"cube" in m->R.drawable.thumb_cube;else->R.drawable.thumb_empty}
    }
    private fun openProject(folder:DocumentFile){startActivity(Intent(this,EditorActivity::class.java).apply{folder.findFile("model.glb")?.let{putExtra(EditorActivity.EXTRA_PROJECT_MODEL_URI,it.uri.toString())}})}
    private fun showFolderDialog(){toast("Choose a folder where Luxe should keep project subfolders");chooseFolder.launch(rootUri())}
    private fun refreshFolderLabel(){folderText.text=rootDocument()?.name?.let{"Project folder  /  $it"}?:"No project folder selected"}
    private fun displayFolderName()=rootDocument()?.name?:"Project folder"
    private fun rootUri():Uri?=prefs.getString(KEY_ROOT_URI,null)?.let(Uri::parse)
    private fun rootDocument():DocumentFile?=rootUri()?.let{DocumentFile.fromTreeUri(this,it)}
    private fun emptyLabel(msg:String)=text(msg,13f,0xff777777.toInt()).apply{gravity=Gravity.CENTER;setPadding(0,dp(70),0,0)}
    private fun text(value:String,size:Float,color:Int)=TextView(this).apply{text=value;textSize=size;setTextColor(color);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL}
    private fun smallButton(label:String,primary:Boolean,action:()->Unit)=TextView(this).apply{text=label;textSize=12f;gravity=Gravity.CENTER;setTextColor(if(primary)Color.WHITE else 0xffd0d0d0.toInt());setPadding(dp(12),0,dp(12),0);setBackgroundResource(if(primary)R.drawable.hub_primary_button else R.drawable.hub_secondary_button);setOnClickListener{action()}}
    private fun toast(msg:String)=Toast.makeText(this,msg,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object{private const val KEY_ROOT_URI="project_root_uri"}
}
