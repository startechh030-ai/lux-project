package luxe.texture3d.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/** Phase 5 editor chrome. Editing actions are deliberately visual placeholders until MeshLibs. */
class EditorChromeView(
    context: Context,
    private val onFiles: () -> Unit,
    private val onSave: () -> Unit,
    private val onDeveloperTools: () -> Unit
) : FrameLayout(context) {
    private val scale = min(resources.displayMetrics.widthPixels / 1600f, resources.displayMetrics.heightPixels / 720f).coerceAtLeast(.55f)
    private fun px(v: Int) = (v * scale).toInt().coerceAtLeast(1)
    private val blue = 0xff7dc4ee.toInt()
    private val text = 0xffd6dbe1.toInt()
    private val muted = 0xff8d969f.toInt()
    private var activeTab = "Edit"
    private val tabViews = linkedMapOf<String, TextView>()
    private val contextRow = LinearLayout(context)
    private val rail = LinearLayout(context)

    init {
        clipChildren = false
        addView(buildTopBar(), LayoutParams(-1, px(45), Gravity.TOP))
        contextRow.orientation = LinearLayout.HORIZONTAL
        contextRow.gravity = Gravity.CENTER_VERTICAL
        contextRow.setPadding(px(10), 0, px(8), 0)
        contextRow.background = bg(0xe815181b.toInt(), 0xff30363c.toInt(), 0)
        addView(contextRow, LayoutParams(px(790), px(44), Gravity.TOP or Gravity.START).apply { topMargin = px(45); leftMargin = px(54) })
        rail.orientation = LinearLayout.VERTICAL
        rail.gravity = Gravity.TOP
        rail.setPadding(px(5), px(7), px(5), px(5))
        rail.background = bg(0xee111416.toInt(), 0xff30363c.toInt(), 0)
        addView(rail, LayoutParams(px(54), px(520), Gravity.TOP or Gravity.START).apply { topMargin = px(45) })
        rebuildContext()
        buildRail()
        addView(label("DEV", 9f, muted).apply {
            gravity = Gravity.CENTER
            background = bg(0xd9181c20.toInt(), 0xff343b42.toInt(), 3)
            setOnClickListener { onDeveloperTools() }
        }, LayoutParams(px(46), px(27), Gravity.BOTTOM or Gravity.START).apply { leftMargin = px(5); bottomMargin = px(7) })
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), 0, px(8), 0); background = bg(0xff101315.toInt(), 0xff30363c.toInt(), 0)
        }
        bar.addView(label("L", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(px(42), -1))
        val scrollContent = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("File", "Edit", "Sculpt", "Texturing", "Animation", "Node Graph", "UVs", "Full Rendering", "More").forEach { name ->
            val tab = label(name, 12f, text).apply {
                gravity = Gravity.CENTER; setPadding(px(13), 0, px(13), 0)
                setOnClickListener {
                    if (name == "File") onFiles() else { activeTab = name; refreshTabs(); rebuildContext(); if (name != "Edit") Toast.makeText(context, "$name workspace is a Phase 5 preview", Toast.LENGTH_SHORT).show() }
                }
                setOnLongClickListener { if (name == "File") { onSave(); true } else false }
            }
            tabViews[name] = tab
            scrollContent.addView(tab, LinearLayout.LayoutParams(-2, px(37)))
        }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(scrollContent) }
        bar.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(label("Edit Preview", 10f, text).apply { gravity = Gravity.CENTER; background = bg(0xff20262b.toInt(), 0xff414a52.toInt(), 3) }, LinearLayout.LayoutParams(px(112), px(29)).apply { rightMargin = px(7) })
        bar.addView(label("●  Matcap", 10f, blue).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(px(82), -1))
        refreshTabs()
        return bar
    }

    private fun refreshTabs() = tabViews.forEach { (name, view) ->
        view.setTextColor(if (name == activeTab) Color.WHITE else text)
        view.background = if (name == activeTab) bg(0xff22333e.toInt(), blue, 2) else null
    }

    private fun rebuildContext() {
        contextRow.removeAllViews()
        val items = when (activeTab) {
            "Edit" -> listOf("Object", "Vertex", "Edge", "Face", "Pivot", "Global", "Snap", "Symmetry")
            "Sculpt" -> listOf("Draw", "Smooth", "Clay", "Inflate", "Radius", "Strength", "Symmetry")
            "Texturing" -> listOf("Paint", "Erase", "Fill", "Material", "Channel", "Brush")
            "Animation" -> listOf("Pose", "Key", "Playback", "Timeline", "Dope Sheet")
            "Node Graph" -> listOf("Add Node", "Connect", "Frame", "Search")
            "UVs" -> listOf("Select", "Seam", "Unwrap", "Pack", "Align")
            "Full Rendering" -> listOf("Camera", "Lighting", "Preview", "Output", "Render")
            else -> listOf("Workspace", "Options")
        }
        items.forEachIndexed { i, name ->
            contextRow.addView(label(name, 10f, if (i == 0) blue else text).apply {
                gravity = Gravity.CENTER; background = bg(if (i == 0) 0xff20323c.toInt() else 0xff202428.toInt(), if (i == 0) 0xff4f8daf.toInt() else 0xff363d43.toInt(), 3)
                setOnClickListener { Toast.makeText(context, "$name is a UI placeholder", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(px(if (name.length > 8) 84 else 68), px(28)).apply { rightMargin = px(5) })
        }
    }

    private fun buildRail() {
        val tools = listOf("⌖" to "Select", "◒" to "Bevel", "△" to "Selection Mode", "✥" to "Transform", "⇧" to "Extrude", "▣" to "Inset", "✂" to "Cut", "•••" to "More Tools")
        tools.forEachIndexed { index, (icon, name) ->
            rail.addView(label(icon, if (index == 7) 13f else 19f, if (index == 0) blue else text).apply {
                gravity = Gravity.CENTER
                contentDescription = name
                background = bg(if (index == 0) 0xff203540.toInt() else Color.TRANSPARENT, if (index == 0) 0xff5ca4cc.toInt() else 0x00363d43, 3)
                setOnClickListener { Toast.makeText(context, "$name — MeshLibs placeholder", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(px(44), px(48)).apply { bottomMargin = px(5) })
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size * scale.coerceAtMost(1.15f); setTextColor(color); includeFontPadding = false; maxLines = 1
    }

    private fun bg(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); if (stroke ushr 24 != 0) setStroke(px(1), stroke); cornerRadius = px(radius).toFloat()
    }
}
