package luxe.texture3d.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private data class Template(val title:String,val asset:String?,val icon:Int)

    private val templates=listOf(
        Template("Empty scene",null,R.drawable.thumb_empty),
        Template("Cube","cube.glb",R.drawable.thumb_cube),
        Template("Sphere","sphere.glb",R.drawable.thumb_sphere),
        Template("Cylinder","cylinder.glb",R.drawable.thumb_cylinder),
        Template("Capsule","capsule.glb",R.drawable.thumb_capsule),
        Template("Plane","plane.glb",R.drawable.thumb_plane),
        Template("Round Box","round_box.glb",R.drawable.thumb_round_box),
        Template("Torus","torus.glb",R.drawable.thumb_torus),
        Template("Trolls","trolls.glb",R.drawable.thumb_trolls)
    )

    private lateinit var projectsList:LinearLayout
    private lateinit var folderText:TextView
    private val prefs by lazy { getSharedPreferences("project_hub",MODE_PRIVATE) }

    private val chooseFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){
            runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.edit().putString(KEY_ROOT_URI,uri.toString()).apply()
            refreshFolderLabel();refreshProjects()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xff121416.toInt();window.navigationBarColor=0xff121416.toInt()
        setContentView(buildUi())
        refreshFolderLabel();refreshProjects()
        if(rootUri()==null) showFirstFolderDialog()
    }

    override fun onResume(){super.onResume();if(::projectsList.isInitialized)refreshProjects()}

    private fun buildUi():View{
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff121416.toInt());setPadding(dp(22),dp(16),dp(22),dp(16))}
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        titleRow.addView(TextView(this).apply{text="LUXE TEXTURE3D";textSize=21f;setTextColor(0xffe8f5fb.toInt());setTypeface(typeface,1)},LinearLayout.LayoutParams(0,dp(48),1f))
        titleRow.addView(button("+  New Project"){showNewProjectDialog()},LinearLayout.LayoutParams(-2,dp(44)))
        root.addView(titleRow)

        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        listOf("Local Projects","Marketplace","Settings","Plugin Manager").forEachIndexed{i,name->
            tabs.addView(TextView(this).apply{
                text=name;textSize=14f;gravity=Gravity.CENTER;setPadding(dp(18),0,dp(18),0)
                setTextColor(if(i==0)0xff38bdf8.toInt() else 0xff9da8ae.toInt())
                setBackgroundColor(if(i==0)0xff202a30.toInt() else Color.TRANSPARENT)
                setOnClickListener{if(i!=0)Toast.makeText(this@MainActivity,"$name — Coming soon",Toast.LENGTH_SHORT).show()}
            },LinearLayout.LayoutParams(-2,dp(42)))
        }
        root.addView(tabs)

        val folderRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(12),0,dp(10))}
        folderText=TextView(this).apply{textSize=12f;setTextColor(0xffaebbc2.toInt())}
        folderRow.addView(folderText,LinearLayout.LayoutParams(0,dp(38),1f))
        folderRow.addView(button("Choose Folder"){chooseFolder.launch(rootUri())},LinearLayout.LayoutParams(-2,dp(38)))
        root.addView(folderRow)

        projectsList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(projectsList)},LinearLayout.LayoutParams(-1,0,1f))
        return root
    }

    private fun refreshFolderLabel(){folderText.text=rootUri()?.let{"Project directory: $it"}?:"No project directory selected"}

    private fun refreshProjects(){
        projectsList.removeAllViews()
        val root=rootDocument()
        if(root==null){projectsList.addView(emptyLabel("Choose a project folder to begin."));return}
        val dirs=runCatching{root.listFiles().filter{it.isDirectory}.sortedBy{it.name?.lowercase()}}.getOrDefault(emptyList())
        if(dirs.isEmpty()){projectsList.addView(emptyLabel("No local projects yet. Create your first project."));return}
        dirs.forEach{dir->projectsList.addView(projectCard(dir))}
    }

    private fun projectCard(dir:DocumentFile):View{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(14),dp(10));setBackgroundResource(R.drawable.panel_bg)}
        row.addView(ImageView(this).apply{setImageResource(projectIcon(dir));setColorFilter(0xffbae6fd.toInt());setPadding(dp(6),dp(6),dp(6),dp(6))},LinearLayout.LayoutParams(dp(54),dp(54)))
        val model=dir.findFile("model.glb")
        row.addView(LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply{text=dir.name?:"Untitled";textSize=16f;setTextColor(0xffeef8fc.toInt())})
            addView(TextView(this@MainActivity).apply{text=if(model!=null)"GLB project" else "Empty scene";textSize=12f;setTextColor(0xff8fa0a8.toInt())})
        },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("Open"){openProject(dir)},LinearLayout.LayoutParams(-2,dp(38)))
        return FrameLayout(this).apply{setPadding(0,0,0,dp(9));addView(row,FrameLayout.LayoutParams(-1,dp(76)))}
    }

    private fun showNewProjectDialog(){
        if(rootDocument()==null){showFirstFolderDialog();return}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),0)}
        val name=EditText(this).apply{hint="Project name";setSingleLine(true)}
        val preview=ImageView(this).apply{setImageResource(templates[0].icon);setPadding(dp(18),dp(18),dp(18),dp(18))}
        val spinner=Spinner(this)
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,templates.map{it.title})
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long){preview.setImageResource(templates[position].icon)}
            override fun onNothingSelected(p:android.widget.AdapterView<*>?){}
        }
        box.addView(name,LinearLayout.LayoutParams(-1,dp(54)))
        box.addView(TextView(this).apply{text="Start with a template";setTextColor(0xffaebbc2.toInt());setPadding(0,dp(10),0,dp(5))})
        box.addView(preview,LinearLayout.LayoutParams(-1,dp(96)))
        box.addView(spinner,LinearLayout.LayoutParams(-1,dp(52)))
        val dialog=AlertDialog.Builder(this).setTitle("New Project").setView(box)
            .setPositiveButton("Jump into Editor",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val title=name.text.toString().trim()
                if(title.isBlank()){name.error="Project name is required";return@setOnClickListener}
                createProject(title,templates[spinner.selectedItemPosition])?.let{project->dialog.dismiss();openProject(project)}
            }
        }
        dialog.show()
    }

    private fun createProject(title:String,template:Template):DocumentFile?{
        val root=rootDocument()?:return null
        val safe=title.replace(Regex("[\\\\/:*?\"<>|]"),"_").trim().take(64)
        if(safe.isBlank()){Toast.makeText(this,"Invalid project name",Toast.LENGTH_SHORT).show();return null}
        if(root.findFile(safe)!=null){Toast.makeText(this,"A project with this name already exists",Toast.LENGTH_LONG).show();return null}
        return runCatching{
            val folder=root.createDirectory(safe)?:error("Could not create project folder")
            val escapedName=safe.replace("\"","\\\"")
            val metadata="{\"name\":\"$escapedName\",\"template\":\"${template.title}\",\"version\":1}"
            folder.createFile("application/json","project.json")?.let{meta->
                contentResolver.openOutputStream(meta.uri)?.use{it.write(metadata.toByteArray())}
            }
            template.asset?.let{assetName->
                val out=folder.createFile("model/gltf-binary","model.glb")?:error("Could not create model file")
                assets.open("templates/$assetName").use{input->contentResolver.openOutputStream(out.uri)?.use{output->input.copyTo(output)}?:error("Could not write template")}
            }
            folder
        }.onFailure{Toast.makeText(this,it.message?:"Project creation failed",Toast.LENGTH_LONG).show()}.getOrNull()
    }

    private fun projectIcon(folder:DocumentFile):Int{
        val metadata=folder.findFile("project.json")?.let{file->
            runCatching{contentResolver.openInputStream(file.uri)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrDefault("")
        }.orEmpty().lowercase()
        return when{
            "sphere" in metadata->R.drawable.thumb_sphere
            "cylinder" in metadata->R.drawable.thumb_cylinder
            "capsule" in metadata->R.drawable.thumb_capsule
            "plane" in metadata->R.drawable.thumb_plane
            "round box" in metadata->R.drawable.thumb_round_box
            "torus" in metadata->R.drawable.thumb_torus
            "trolls" in metadata->R.drawable.thumb_trolls
            "cube" in metadata->R.drawable.thumb_cube
            else->R.drawable.thumb_empty
        }
    }

    private fun openProject(folder:DocumentFile){
        val intent=Intent(this,EditorActivity::class.java)
        folder.findFile("model.glb")?.let{intent.putExtra(EditorActivity.EXTRA_PROJECT_MODEL_URI,it.uri.toString())}
        startActivity(intent)
    }

    private fun showFirstFolderDialog(){AlertDialog.Builder(this).setTitle("Choose Project Directory").setMessage("Select or create a folder in device storage. Luxe will save every project as a subfolder inside it.").setPositiveButton("Choose Folder"){_,_->chooseFolder.launch(null)}.setNegativeButton("Later",null).show()}
    private fun rootUri():Uri?=prefs.getString(KEY_ROOT_URI,null)?.let(Uri::parse)
    private fun rootDocument():DocumentFile?=rootUri()?.let{DocumentFile.fromTreeUri(this,it)}
    private fun emptyLabel(message:String)=TextView(this).apply{text=message;gravity=Gravity.CENTER;textSize=15f;setTextColor(0xff7f9098.toInt());setPadding(0,dp(70),0,0)}
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;setTextColor(0xffdff5ff.toInt());setBackgroundResource(R.drawable.panel_bg);setOnClickListener{action()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    companion object { private const val KEY_ROOT_URI="project_root_uri" }
}
