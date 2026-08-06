package luxe.texture3d.app

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EditorActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var surface: SurfaceView
    private lateinit var cameraInput: CameraInputView
    private lateinit var viewer: ModelViewer
    private lateinit var manipulator: Manipulator
    private lateinit var editorGrid: EditorGrid
    private lateinit var sceneManager: EditorSceneManager
    private lateinit var selectionBounds: SelectionBoundsRenderer
    private val mainHandler=Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var saveButton: TextView
    private lateinit var resourceBrowserPanel: FloatingResourceBrowserPanel
    private var projectSession: ProjectSessionManager? = null
    private var suppressSceneDirty = false
    private var solidSkybox: Skybox? = null
    private var rendering = false

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleSelectedModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleEditorBack()
        })
        Utils.init()
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // This legacy fullscreen flag is reliable across our API 26+ device range.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val root = FrameLayout(this).apply { setBackgroundColor(0xff0f172a.toInt()) }
        surface = SurfaceView(this).apply { setZOrderOnTop(false) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        // A normal transparent View captures input above the hardware-backed
        // SurfaceView. This avoids device-specific SurfaceView touch failures.
        cameraInput = CameraInputView(this).apply {
            onGesture = { gesture -> status.text = "CAMERA INPUT  •  $gesture"; projectSession?.markDirty();if(::saveButton.isInitialized)saveButton.text="Save •" }
            onTap = { x,y -> pickScene(x,y) }
            onDoubleTap = { resetCameraHome() }
        }
        root.addView(cameraInput, FrameLayout.LayoutParams(-1, -1))

        val open = ImageButton(this).apply {
            setImageResource(luxe.texture3d.app.R.drawable.ic_open)
            contentDescription = "Open GLB model"
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setColorFilter(0xffbae6fd.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { resourceBrowserPanel.togglePanel() }
        }
        root.addView(open, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })
        val addModel=TextView(this).apply{text="+ Model";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{openModel.launch(arrayOf("model/gltf-binary","application/octet-stream","*/*"))}}
        root.addView(addModel,FrameLayout.LayoutParams(dp(78),dp(36),Gravity.TOP or Gravity.START).apply{leftMargin=dp(10);topMargin=dp(70)})
        val removeModel=TextView(this).apply{text="− Last";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{if(::sceneManager.isInitialized&&!sceneManager.removeLast())Toast.makeText(this@EditorActivity,"Scene is empty",Toast.LENGTH_SHORT).show()}}
        root.addView(removeModel,FrameLayout.LayoutParams(dp(78),dp(36),Gravity.TOP or Gravity.START).apply{leftMargin=dp(10);topMargin=dp(110)})
        val sceneList=TextView(this).apply{text="Scene";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{showSceneList()}}
        root.addView(sceneList,FrameLayout.LayoutParams(dp(78),dp(36),Gravity.TOP or Gravity.START).apply{leftMargin=dp(10);topMargin=dp(150)})

        saveButton = TextView(this).apply {
            text = "Save"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.hub_primary_button)
            setOnClickListener { saveProject(false) }
        }
        root.addView(saveButton, FrameLayout.LayoutParams(dp(76), dp(42), Gravity.TOP or Gravity.END).apply { rightMargin = dp(12); topMargin = dp(10) })

        val settings = ImageButton(this).apply {
            setImageResource(luxe.texture3d.app.R.drawable.ic_settings)
            contentDescription = "Settings"
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener {
                Toast.makeText(this@EditorActivity, "Settings panel comes next", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(settings, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(11); bottomMargin = dp(12)
        })


        status = TextView(this).apply {
            text = "OPEN A GLB  •  Drag: orbit  •  Pinch: zoom  •  Two fingers: pan"
            setTextColor(0xffbae6fd.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setBackgroundResource(luxe.texture3d.app.R.drawable.panel_bg)
            setPadding(dp(16), 0, dp(16), 0)
        }
        status.alpha = 0f
        root.addView(status, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM))
        resourceBrowserPanel = FloatingResourceBrowserPanel(this,object:ResourceBrowserActions{
            override fun onAddToScene(resourcePath:String)=addAssetFromBrowser(resourcePath)
            override fun onAddToProject(resourcePath:String)=addResourceToProject(resourcePath)
            override fun onOpenProject(projectPath:String)=requestOpenProject(projectPath)
        }).apply { visibility = View.GONE }
        root.addView(resourceBrowserPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyImmersiveMode()

        // One Filament-native camera owner, with our low-latency touch adapter.
        manipulator = Manipulator.Builder()
            .targetPosition(0f, 0.7f, -4f)
            .orbitHomePosition(4f, 3.4f, 4f)
            // Filament defaults to 0.01. A lower value gives the deliberate,
            // weighted response expected from a mobile sculpting viewport.
            .orbitSpeed(0.0035f, 0.0035f)
            .zoomSpeed(0.012f)
            // Pan against a plane through the model target, not Filament's
            // default z=0 plane. This makes pan usable across the viewport.
            .groundPlane(0f, 0f, 1f, 4f)
            .panning(true)
            .viewport(surface.width.coerceAtLeast(1), surface.height.coerceAtLeast(1))
            .build(Manipulator.Mode.ORBIT)
        cameraInput.manipulator = manipulator
        viewer = ModelViewer(surface, manipulator = manipulator)
        loadEnvironment()
        editorGrid = EditorGrid(
            viewer.engine, viewer.scene,
            readAsset("materials/luxe_grid.filamat"),
            readAsset("materials/luxe_lines.filamat")
        )
        selectionBounds=SelectionBoundsRenderer(viewer.engine,viewer.scene,readAsset("materials/luxe_lines.filamat"))
        sceneManager = EditorSceneManager(viewer.engine,viewer.scene,{if(!suppressSceneDirty){projectSession?.markDirty();if(::saveButton.isInitialized)saveButton.text="Save •"}},{record->if(record==null)selectionBounds.clear()else selectionBounds.show(record.worldCenter,record.worldHalfExtent)})
        cameraInput.inputEnabled = true

        intent.getStringExtra(EXTRA_PROJECT_PATH)?.let { openProjectSession(it) }
            ?: intent.getStringExtra(EXTRA_PROJECT_MODEL_URI)?.let { encoded -> runCatching { Uri.parse(encoded) }.getOrNull()?.let { loadGlb(it,false) } }
    }

    private fun loadEnvironment() {
        val ibl = readAsset("environments/shanghai_bund_2k_ibl.ktx")
        KTX1Loader.createIndirectLight(viewer.engine, ibl).also { bundle ->
            viewer.scene.indirectLight = bundle.indirectLight
            viewer.indirectLightCubemap = bundle.cubemap
            bundle.indirectLight?.intensity = 30_000f
        }
        // Keep the HDR only as invisible image-based lighting. The visible
        // background is a neutral Blender-like gray and does not light models.
        solidSkybox = Skybox.Builder()
            .color(0.055f, 0.065f, 0.075f, 1.0f)
            .build(viewer.engine)
        viewer.scene.skybox = solidSkybox
    }

    private fun readAsset(path: String): ByteBuffer {
        val bytes = assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
    }

    private fun pickScene(x:Float,y:Float){if(!::viewer.isInitialized||!::sceneManager.isInitialized)return;viewer.view.pick(x.toInt(),surface.height-y.toInt(),mainHandler){result->if(result.renderable==0)sceneManager.clearSelection()else sceneManager.selectByEntity(result.renderable)}}
    private fun sceneControl(label:String,action:()->Unit)=TextView(this).apply{text=label;gravity=Gravity.CENTER;textSize=11f;setTextColor(0xffd5dbe4.toInt());setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{action()}}
    private fun showSceneList(){
        val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));setBackgroundResource(R.drawable.hub_dialog_bg)}
        val heading=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};heading.addView(TextView(this).apply{text="SCENE  •  ${sceneManager.size} objects";textSize=16f;setTextColor(Color.WHITE);setTypeface(typeface,1)},LinearLayout.LayoutParams(0,dp(38),1f));heading.addView(TextView(this).apply{text="Close";gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(R.drawable.hub_secondary_button);setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(dp(80),dp(34)));panel.addView(heading)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};sceneManager.all().forEach{record->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(5),dp(8),dp(5));setBackgroundResource(if(sceneManager.selection==record.uid)R.drawable.hub_nav_selected else R.drawable.hub_project_row)};row.addView(TextView(this).apply{text=record.name;textSize=12f;setTextColor(Color.WHITE);setOnClickListener{sceneManager.select(record.uid);dialog.dismiss();showSceneList()}},LinearLayout.LayoutParams(0,dp(38),1f));row.addView(sceneControl(if(record.visible)"Hide" else "Show",{sceneManager.setVisible(record.uid,!record.visible);dialog.dismiss();showSceneList()}),LinearLayout.LayoutParams(dp(58),dp(32)));row.addView(sceneControl(if(record.locked)"Unlock" else "Lock",{sceneManager.setLocked(record.uid,!record.locked);dialog.dismiss();showSceneList()}),LinearLayout.LayoutParams(dp(62),dp(32)).apply{leftMargin=dp(4)});row.addView(sceneControl("Rename",{dialog.dismiss();showRenameInstance(record.uid,record.name)}),LinearLayout.LayoutParams(dp(68),dp(32)).apply{leftMargin=dp(4)});row.addView(sceneControl("Delete",{sceneManager.remove(record.uid);dialog.dismiss();showSceneList()}),LinearLayout.LayoutParams(dp(60),dp(32)).apply{leftMargin=dp(4)});list.addView(row,LinearLayout.LayoutParams(-1,dp(48)).apply{bottomMargin=dp(4)})};if(sceneManager.size==0)list.addView(TextView(this).apply{text="Scene is empty";gravity=Gravity.CENTER;textSize=13f;setTextColor(0xff777777.toInt())},LinearLayout.LayoutParams(-1,dp(80)));panel.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(-1,0,1f));dialog.setContentView(panel);dialog.show();dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.72f).toInt(),(resources.displayMetrics.heightPixels*.72f).toInt())}
    }
    private fun showRenameInstance(uid:String,current:String){val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));setBackgroundResource(R.drawable.hub_dialog_bg)};val input=android.widget.EditText(this).apply{setText(current);setSingleLine(true);setTextColor(Color.WHITE);background=getDrawable(R.drawable.hub_field_bg)};panel.addView(TextView(this).apply{text="Rename Scene Object";textSize=16f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,dp(36)));panel.addView(input,LinearLayout.LayoutParams(-1,dp(42)));val save=TextView(this).apply{text="Save";gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(R.drawable.hub_primary_button);setOnClickListener{val name=input.text.toString().trim();if(name.isNotBlank()){sceneManager.rename(uid,name);dialog.dismiss();showSceneList()}}};panel.addView(save,LinearLayout.LayoutParams(-1,dp(38)).apply{topMargin=dp(8)});dialog.setContentView(panel);dialog.show();dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.42f).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT)}}

    private fun addAssetFromBrowser(path:String){
        val folder=File(path);runCatching{val validation=ResourceValidation.asset(folder);require(validation.status!="Invalid"){validation.messages.joinToString("; ")};sceneManager.addGltf(folder,folder.name);resourceBrowserPanel.hidePanel();Toast.makeText(this,"Asset added to scene",Toast.LENGTH_SHORT).show()}.onFailure{Toast.makeText(this,it.message?:"Unable to add asset",Toast.LENGTH_LONG).show()}
    }
    private fun addResourceToProject(path:String){
        val manager=projectSession?:run{Toast.makeText(this,"No project session is open",Toast.LENGTH_SHORT).show();return};val file=File(path);val library=AppFileSystem(this).library;val storedPath=runCatching{file.relativeTo(library).invariantSeparatorsPath}.getOrDefault(file.absolutePath);val type=when(file.extension.lowercase()){"ulelement"->"ulelement";"anim"->"animation";"texture"->"texture";else->"resource"};manager.addResourceReference(storedPath,type);saveButton.text="Save •";Toast.makeText(this,"${type.replaceFirstChar{it.uppercase()}} added to project",Toast.LENGTH_SHORT).show()
    }
    private fun requestOpenProject(path:String){
        val manager=projectSession
        if(manager==null||!manager.dirty){switchProject(path);return}
        val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(14));setBackgroundResource(R.drawable.hub_dialog_bg)
            addView(TextView(this@EditorActivity).apply{text="Open another project?";textSize=17f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,dp(34)))
            addView(TextView(this@EditorActivity).apply{text="Save or discard current changes before opening the selected project.";textSize=12f;setTextColor(0xffaab4c2.toInt())},LinearLayout.LayoutParams(-1,dp(50)))
        }
        fun projectButton(label:String,primary:Boolean,click:()->Unit)=TextView(this).apply{
            text=label;gravity=Gravity.CENTER;textSize=12f;setTextColor(Color.WHITE)
            setBackgroundResource(if(primary)R.drawable.hub_primary_button else R.drawable.hub_secondary_button)
            setOnClickListener{click()}
        }
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END}
        row.addView(projectButton("Cancel",false,{dialog.dismiss()}),LinearLayout.LayoutParams(dp(82),dp(38)))
        row.addView(projectButton("Discard",false,{dialog.dismiss();manager.closeDiscard();switchProject(path)}),LinearLayout.LayoutParams(dp(90),dp(38)).apply{leftMargin=dp(7)})
        row.addView(projectButton("Save & Open",true,{
            dialog.dismiss()
            runCatching{manager.writeScene(captureSceneState());manager.save()}.onSuccess{switchProject(path)}.onFailure{Toast.makeText(this@EditorActivity,it.message?:"Save failed",Toast.LENGTH_LONG).show()}
        }),LinearLayout.LayoutParams(dp(112),dp(38)).apply{leftMargin=dp(7)})
        panel.addView(row);dialog.setContentView(panel);dialog.show();dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun switchProject(path:String){startActivity(Intent(this,EditorActivity::class.java).putExtra(EXTRA_PROJECT_PATH,path));finish()}

    private fun openProjectSession(path:String){
        runCatching{
            val manager=ProjectSessionManager(this,path);projectSession=manager;val opened=manager.open()
            if(opened.recoveryAvailable)showRecoveryDialog(manager) else restoreSessionScene(manager,opened.modelFile)
        }.onFailure{Toast.makeText(this,it.message?:"Unable to open ULX project",Toast.LENGTH_LONG).show()}
    }
    private fun loadSessionModel(file:File?){file?.takeIf{it.isFile}?.let{loadGlb(Uri.fromFile(it),false,"instance-main")}}
    private fun restoreSessionScene(manager:ProjectSessionManager,fallback:File?){
        val sceneState=manager.scene();val instances=sceneState.optJSONArray("instances");if(instances==null||instances.length()==0){loadSessionModel(fallback);return}
        val savedSelection=sceneState.optString("selection").takeIf{it.isNotBlank()&&it!="null"}
        suppressSceneDirty=true
        try{for(i in 0 until instances.length()){
            val item=instances.optJSONObject(i)?:continue;val uid=item.optString("instanceUid").ifBlank{"instance-$i"};val name=item.optString("name","Model ${i+1}");val source=item.optString("source");val file=File(source).takeIf{it.isAbsolute}?:File(manager.sessionDir,source)
            runCatching{if(file.isDirectory&&File(file,"model.gltf").isFile)sceneManager.addGltf(file,name,uid)else if(file.isFile)loadGlb(Uri.fromFile(file),false,uid)}
            sceneManager.setVisible(uid,item.optBoolean("visible",true));sceneManager.setLocked(uid,item.optBoolean("locked",false))
        };savedSelection?.let{sceneManager.select(it)}}finally{suppressSceneDirty=false;saveButton.text="Save"}
    }
    private fun captureSceneState():org.json.JSONObject{
        val scene=projectSession?.scene()?:org.json.JSONObject();val eye=DoubleArray(3);val target=DoubleArray(3);val up=DoubleArray(3)
        if(::manipulator.isInitialized){manipulator.getLookAt(eye,target,up);scene.put("camera",org.json.JSONObject().put("eye",org.json.JSONArray(eye.toList())).put("target",org.json.JSONArray(target.toList())).put("up",org.json.JSONArray(up.toList())))}
        if(::sceneManager.isInitialized){scene.put("instances",sceneManager.serialize());scene.put("selection",sceneManager.selection?:org.json.JSONObject.NULL)}
        scene.put("modifiedAt",System.currentTimeMillis());return scene
    }
    private fun saveProject(exitAfter:Boolean){val manager=projectSession;if(manager==null){Toast.makeText(this,"No ULX project session is open",Toast.LENGTH_SHORT).show();return};runCatching{manager.writeScene(captureSceneState());manager.save()}.onSuccess{saveButton.text="Save";Toast.makeText(this,"Project saved",Toast.LENGTH_SHORT).show();if(exitAfter){manager.closeClean();finish()}}.onFailure{Toast.makeText(this,it.message?:"Project save failed",Toast.LENGTH_LONG).show()}}
    private fun handleEditorBack(){val manager=projectSession;if(manager==null||!manager.dirty){manager?.closeClean();finish()}else showDiscardEditorDialog()}
    private fun showRecoveryDialog(manager:ProjectSessionManager){
        val dialog=Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(18),dp(22),dp(16));setBackgroundResource(R.drawable.hub_dialog_bg);addView(TextView(this@EditorActivity).apply{text="Recover unsaved session?";textSize=18f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,dp(36)));addView(TextView(this@EditorActivity).apply{text="Luxe found an autosave from an interrupted editor session.";textSize=13f;setTextColor(0xffaeb7c4.toInt())},LinearLayout.LayoutParams(-1,dp(52)))}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END};fun b(t:String,primary:Boolean,go:()->Unit)=TextView(this).apply{text=t;textSize=12f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundResource(if(primary)R.drawable.hub_primary_button else R.drawable.hub_secondary_button);setOnClickListener{go()}}
        actions.addView(b("Last Saved",false){dialog.dismiss();manager.openLastSaved();restoreSessionScene(manager,File(manager.sessionDir,"model.glb").takeIf{it.isFile})},LinearLayout.LayoutParams(dp(110),dp(40)));actions.addView(b("Recover",true){dialog.dismiss();manager.recoverAutosave();saveButton.text="Save •";restoreSessionScene(manager,File(manager.sessionDir,"model.glb").takeIf{it.isFile});manager.markDirty();saveButton.text="Save •"},LinearLayout.LayoutParams(dp(100),dp(40)).apply{leftMargin=dp(8)});panel.addView(actions);dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(false);dialog.show();dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.48f).toInt(),ViewGroup.LayoutParams.WRAP_CONTENT)}}

    private fun resetCameraHome() {
        if (::manipulator.isInitialized) {
            manipulator.jumpToBookmark(manipulator.homeBookmark)
        }
    }

    private fun showDiscardEditorDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            setBackgroundResource(R.drawable.hub_dialog_bg)
            addView(TextView(this@EditorActivity).apply {
                text = "Discard unsaved changes?"; textSize = 18f; setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, dp(36)))
            addView(TextView(this@EditorActivity).apply {
                text = "You are about to close the editor. Any unsaved changes will be discarded."
                textSize = 13f; setTextColor(0xffaeb7c4.toInt())
            }, LinearLayout.LayoutParams(-1, dp(58)))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun modalButton(label: String, primary: Boolean, click: () -> Unit) = TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundResource(if (primary) R.drawable.hub_primary_button else R.drawable.hub_secondary_button)
            setOnClickListener { click() }
        }
        actions.addView(modalButton("Cancel", false) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(82), dp(40)))
        actions.addView(modalButton("Discard", false) { dialog.dismiss(); projectSession?.closeDiscard(); finish() }, LinearLayout.LayoutParams(dp(94), dp(40)).apply { leftMargin = dp(8) })
        actions.addView(modalButton("Save & Exit", true) { dialog.dismiss(); saveProject(true) }, LinearLayout.LayoutParams(dp(118), dp(40)).apply { leftMargin = dp(8) })
        panel.addView(actions, LinearLayout.LayoutParams(-1, dp(46)))
        dialog.setContentView(panel); dialog.setCanceledOnTouchOutside(false); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.76f }
            setLayout((resources.displayMetrics.widthPixels * 0.48f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun handleSelectedModel(uri: Uri) { loadGlb(uri,true) }

    private fun loadGlb(uri: Uri, markDirty: Boolean = true, forcedUid:String?=null) {
        try {
            val name=displayName(uri);if(!name.lowercase().endsWith(".glb"))throw IllegalArgumentException("Please choose a .glb file")
            val size=selectedFileSize(uri);val limit=safeGlbImportLimit();if(size<=0)error("Android did not provide this GLB's size");if(size>limit)error("This ${formatMb(size)} MB GLB exceeds this device's ${formatMb(limit)} MB safe import limit")
            val uid=forcedUid?:"instance-${java.util.UUID.randomUUID()}";val source=persistSceneSource(uri,uid,markDirty);val wasEmpty=sceneManager.size==0
            cameraInput.inputEnabled=false;manipulator.grabEnd();suppressSceneDirty=!markDirty;try{sceneManager.addGlb(readDirectBuffer(uri,size),name,source,uid)}finally{suppressSceneDirty=false}
            if(wasEmpty)manipulator.jumpToBookmark(manipulator.homeBookmark);cameraInput.inputEnabled=true
            if(markDirty){projectSession?.markDirty();saveButton.text="Save •"};status.text="$name • ${sceneManager.size} scene asset(s)"
        }catch(_:OutOfMemoryError){cameraInput.inputEnabled=true;Toast.makeText(this,"Not enough memory for this model",Toast.LENGTH_LONG).show()}
        catch(e:Exception){cameraInput.inputEnabled=true;Toast.makeText(this,e.message?:"Could not add GLB",Toast.LENGTH_LONG).show()}
    }
    private fun persistSceneSource(uri:Uri,uid:String,copyIntoSession:Boolean):String{
        val session=projectSession?:return uri.toString();val sourceFile=uri.path?.let(::File)
        if(sourceFile!=null&&sourceFile.absolutePath.startsWith(session.sessionDir.absolutePath+File.separator))return sourceFile.relativeTo(session.sessionDir).invariantSeparatorsPath
        if(!copyIntoSession)return sourceFile?.absolutePath?:uri.toString()
        val target=File(session.sessionDir,"local/$uid.glb");target.parentFile?.mkdirs();if(uri.scheme=="file")FileInputStream(sourceFile!!).use{input->java.io.FileOutputStream(target).use{input.copyTo(it,256*1024)}}else contentResolver.openInputStream(uri)?.use{input->java.io.FileOutputStream(target).use{input.copyTo(it,256*1024)}}?:error("Unable to copy model into project session");return target.relativeTo(session.sessionDir).invariantSeparatorsPath
    }

    private fun selectedFileSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let(::File)?.length() ?: -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }

    private fun safeGlbImportLimit(): Long {
        val memoryClassMb = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong()
        // GLB resources expand after parsing. Keep source data below 20% of the
        // app heap, with conservative bounds for weak and high-memory devices.
        val limitMb = (memoryClassMb / 5L).coerceIn(32L, 128L)
        return limitMb * 1024L * 1024L
    }

    private fun readDirectBuffer(uri: Uri, size: Long): ByteBuffer {
        if (size > Int.MAX_VALUE) throw IllegalArgumentException("GLB is too large for Android")
        val output = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: throw IllegalStateException("Invalid project model path")
            FileInputStream(file).channel.use { channel -> while (output.hasRemaining()) { if (channel.read(output) < 0) break } }
        } else {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("The selected file could not be opened")
            descriptor.use { pfd -> FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                while (output.hasRemaining()) { if (channel.read(output) < 0) break }
            }}
        }
        if (output.position() != size.toInt()) {
            throw IllegalStateException("The GLB could not be read completely")
        }
        output.flip()
        return output
    }

    private fun formatMb(bytes: Long) = bytes / (1024L * 1024L)

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let(::File)?.name ?: "model.glb"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "model.glb"
    }


    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onResume() { super.onResume(); applyImmersiveMode(); rendering = true; Choreographer.getInstance().postFrameCallback(this) }
    override fun onPause() { projectSession?.takeIf{it.dirty}?.let{runCatching{it.writeScene(captureSceneState());it.autosave()}};rendering = false; Choreographer.getInstance().removeFrameCallback(this); super.onPause() }
    override fun onDestroy(){if(::sceneManager.isInitialized)runCatching{sceneManager.destroy()};if(::selectionBounds.isInitialized)runCatching{selectionBounds.destroy()};super.onDestroy()}
    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        if(::sceneManager.isInitialized)sceneManager.update()
        // ModelViewer presents the shared Scene; EditorSceneManager owns its multiple glTF assets.
        viewer.render(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_PATH = "luxe.project.path"
        const val EXTRA_PROJECT_MODEL_URI = "luxe.project.model.uri" // legacy fallback
    }
}
