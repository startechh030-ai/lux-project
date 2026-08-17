package luxe.texture3d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/** Phase 5 WIP workspace chrome. Mesh controls remain placeholders until MeshLibs. */
class EditorChromeView(
    context: Context,
    private val onFiles: () -> Unit,
    private val onSave: () -> Unit,
    private val onDeveloperTools: () -> Unit
) : FrameLayout(context) {
    private val scale = min(resources.displayMetrics.widthPixels / 1600f, resources.displayMetrics.heightPixels / 720f).coerceAtLeast(.55f)
    private fun px(v: Int) = (v * scale).toInt().coerceAtLeast(1)
    private val blue = 0xff79bfe8.toInt()
    private val text = 0xffd5dae0.toInt()
    private val muted = 0xff8b949d.toInt()
    private var activeTab = "Edit"
    private var activeTool = 0
    private val tabViews = linkedMapOf<String, TextView>()
    private val contextRow = LinearLayout(context)
    private val rail = LinearLayout(context)
    private val dropdown = LinearLayout(context)

    init {
        clipChildren = false
        addView(buildTopBar(), LayoutParams(-1, px(42), Gravity.TOP))

        contextRow.orientation = LinearLayout.HORIZONTAL
        contextRow.gravity = Gravity.CENTER_VERTICAL
        contextRow.setPadding(px(8), 0, px(6), 0)
        contextRow.background = bg(0xf015181b.toInt(), 0xff30363c.toInt(), 0)
        addView(contextRow, LayoutParams(px(720), px(38), Gravity.TOP or Gravity.START).apply { topMargin = px(42); leftMargin = px(48) })

        rail.orientation = LinearLayout.VERTICAL
        rail.gravity = Gravity.TOP
        rail.setPadding(px(4), px(5), px(4), px(4))
        rail.background = bg(0xf2111416.toInt(), 0xff30363c.toInt(), 0)
        addView(rail, LayoutParams(px(48), px(430), Gravity.TOP or Gravity.START).apply { topMargin = px(42) })

        dropdown.orientation = LinearLayout.VERTICAL
        dropdown.setPadding(px(8), px(7), px(8), px(7))
        dropdown.background = bg(0xff1a1e22.toInt(), 0xff414951.toInt(), 3)
        dropdown.elevation = px(12).toFloat()
        dropdown.visibility = GONE
        addView(dropdown, LayoutParams(px(205), -2, Gravity.TOP or Gravity.START).apply { topMargin = px(40) })

        rebuildContext()
        rebuildRail()
        addView(label("DEV", 8f, muted).apply {
            gravity = Gravity.CENTER
            background = bg(0xd9181c20.toInt(), 0xff343b42.toInt(), 2)
            setOnClickListener { onDeveloperTools() }
        }, LayoutParams(px(40), px(23), Gravity.BOTTOM or Gravity.START).apply { leftMargin = px(4); bottomMargin = px(5) })
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(5), 0, px(7), 0); background = bg(0xff0e1113.toInt(), 0xff30363c.toInt(), 0)
        }
        bar.addView(LuxeToolIconView(context, ToolIcon.LOGO, Color.WHITE, false), LinearLayout.LayoutParams(px(38), px(38)))
        bar.addView(label("WIP", 9f, blue).apply {
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = bg(0xff172a35.toInt(), 0xff315a70.toInt(), 2)
        }, LinearLayout.LayoutParams(px(42), px(24)).apply { rightMargin = px(5) })

        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("File", "Edit", "Sculpt", "Texturing", "Animation", "Node Graph", "UVs", "Full Rendering", "More").forEach { name ->
            val tab = label("$name  ▾", 11f, text).apply {
                gravity = Gravity.CENTER; setPadding(px(10), 0, px(10), 0)
                setOnClickListener { anchor ->
                    if (name != "File") { activeTab = name; refreshTabs(); rebuildContext() }
                    showDropdown(name, anchor)
                }
            }
            tabViews[name] = tab
            tabs.addView(tab, LinearLayout.LayoutParams(-2, px(34)))
        }
        bar.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(label("WIP Preview  ▾", 9.5f, text).apply {
            gravity = Gravity.CENTER; background = bg(0xff20262b.toInt(), 0xff414a52.toInt(), 2)
            setOnClickListener { showDropdown("WIP Preview", it) }
        }, LinearLayout.LayoutParams(px(112), px(27)).apply { rightMargin = px(6) })
        bar.addView(label("●  Matcap", 9.5f, blue).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(px(76), -1))
        refreshTabs()
        return bar
    }

    private fun refreshTabs() = tabViews.forEach { (name, view) ->
        view.setTextColor(if (name == activeTab) Color.WHITE else text)
        view.background = if (name == activeTab) bg(0xff21323c.toInt(), 0xff4d85a3.toInt(), 2) else null
    }

    private fun showDropdown(section: String, anchor: View) {
        dropdown.removeAllViews()
        dropdown.addView(label(section.uppercase(), 9f, blue).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL; setPadding(px(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, px(27)))
        val entries = when (section) {
            "File" -> listOf("Open Resource Browser" to true, "Save Project" to true, "Recent Files" to false, "Import…" to false)
            "WIP Preview" -> listOf("Matcap" to false, "Skeleton Shading" to false, "Wire Overlay" to false, "Preview Settings" to false)
            else -> listOf("Workspace options" to false, "Tool presets" to false, "Customize toolbar" to false)
        }
        entries.forEach { (name, functional) ->
            dropdown.addView(label(if (functional) name else "$name   —", 10.5f, if (functional) text else muted).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(9), 0, px(6), 0)
                background = bg(0xff20252a.toInt(), 0x00343b42, 2)
                setOnClickListener {
                    dropdown.visibility = GONE
                    when (name) {
                        "Open Resource Browser" -> onFiles()
                        "Save Project" -> onSave()
                        else -> Toast.makeText(context, "$name is placeholder data", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(-1, px(31)).apply { topMargin = px(3) })
        }
        val at = IntArray(2); anchor.getLocationOnScreen(at)
        (dropdown.layoutParams as LayoutParams).apply { leftMargin = at[0].coerceAtMost(width - px(215)).coerceAtLeast(0); topMargin = px(40) }
        dropdown.visibility = VISIBLE
        dropdown.bringToFront()
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
            contextRow.addView(label(name, 9.5f, if (i == 0) blue else text).apply {
                gravity = Gravity.CENTER
                background = bg(if (i == 0) 0xff20323c.toInt() else 0xff202428.toInt(), if (i == 0) 0xff4f8daf.toInt() else 0xff363d43.toInt(), 2)
                setOnClickListener { Toast.makeText(context, "$name is a UI placeholder", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(px(if (name.length > 8) 78 else 62), px(25)).apply { rightMargin = px(4) })
        }
    }

    private fun rebuildRail() {
        rail.removeAllViews()
        val tools = listOf(ToolIcon.SELECT, ToolIcon.BEVEL, ToolIcon.COMPONENTS, ToolIcon.TRANSFORM, ToolIcon.EXTRUDE, ToolIcon.INSET, ToolIcon.CUT, ToolIcon.MORE)
        val names = listOf("Select", "Bevel", "Selection Mode", "Transform", "Extrude", "Inset", "Cut", "More Tools")
        tools.forEachIndexed { index, icon ->
            rail.addView(LuxeToolIconView(context, icon, if (index == activeTool) blue else text, index == activeTool).apply {
                contentDescription = names[index]
                setOnClickListener { activeTool = index; rebuildRail(); Toast.makeText(context, "${names[index]} — MeshLibs placeholder", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(px(40), px(40)).apply { bottomMargin = px(4) })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dropdown.visibility == VISIBLE && event.action == MotionEvent.ACTION_DOWN) { dropdown.visibility = GONE; return true }
        return false
    }

    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size * scale.coerceAtMost(1.15f); setTextColor(color); includeFontPadding = false; maxLines = 1
    }
    private fun bg(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); if (stroke ushr 24 != 0) setStroke(px(1), stroke); cornerRadius = px(radius).toFloat()
    }
}

private enum class ToolIcon { LOGO, SELECT, BEVEL, COMPONENTS, TRANSFORM, EXTRUDE, INSET, CUT, MORE }

/** Small original vector icon renderer; avoids font glyph and external icon dependencies. */
private class LuxeToolIconView(context: Context, private val icon: ToolIcon, color: Int, active: Boolean) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.45f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val activeFill = if (active) 0xff203540.toInt() else Color.TRANSPARENT
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.drawColor(activeFill)
        val w=width.toFloat(); val h=height.toFloat(); val x=w/2; val y=h/2; val s=min(w,h)*.26f
        when(icon) {
            ToolIcon.LOGO -> { val p=Path();p.moveTo(x-s*.7f,y-s);p.lineTo(x-s*.7f,y+s);p.lineTo(x+s,y+s);p.lineTo(x+s*.55f,y+s*.45f);p.lineTo(x-s*.1f,y+s*.45f);p.lineTo(x-s*.1f,y-s);p.close();c.drawPath(p,fill) }
            ToolIcon.SELECT -> { val p=Path();p.moveTo(x-s*.8f,y-s);p.lineTo(x+s*.75f,y);p.lineTo(x+.05f*s,y+s*.2f);p.lineTo(x+s*.45f,y+s);p.lineTo(x+s*.05f,y+s*.15f);p.lineTo(x-s*.55f,y+s*.7f);p.close();c.drawPath(p,line) }
            ToolIcon.BEVEL -> { c.drawRoundRect(RectF(x-s,y-s,x+s,y+s),s*.35f,s*.35f,line);c.drawLine(x-s*.55f,y+s,x-s,y+s*.55f,line);c.drawLine(x+s*.55f,y-s,x+s,y-s*.55f,line) }
            ToolIcon.COMPONENTS -> { c.drawCircle(x-s*.7f,y+s*.55f,s*.17f,fill);c.drawCircle(x+s*.7f,y+s*.55f,s*.17f,fill);c.drawCircle(x,y-s*.65f,s*.17f,fill);val p=Path();p.moveTo(x,y-s*.65f);p.lineTo(x-s*.7f,y+s*.55f);p.lineTo(x+s*.7f,y+s*.55f);p.close();c.drawPath(p,line) }
            ToolIcon.TRANSFORM -> { c.drawLine(x-s,y,x+s,y,line);c.drawLine(x,y-s,x,y+s,line);c.drawLine(x-s,y,x-s*.55f,y-s*.3f,line);c.drawLine(x-s,y,x-s*.55f,y+s*.3f,line);c.drawLine(x+s,y,x+s*.55f,y-s*.3f,line);c.drawLine(x+s,y,x+s*.55f,y+s*.3f,line);c.drawCircle(x,y,s*.18f,fill) }
            ToolIcon.EXTRUDE -> { c.drawRect(x-s,y-s*.25f,x+s*.15f,y+s*.75f,line);c.drawRect(x-s*.15f,y-s*.75f,x+s,y+s*.25f,line);c.drawLine(x+s*.15f,y-s*.25f,x+s,y-s*.75f,line) }
            ToolIcon.INSET -> { c.drawRect(x-s,y-s,x+s,y+s,line);c.drawRect(x-s*.48f,y-s*.48f,x+s*.48f,y+s*.48f,line) }
            ToolIcon.CUT -> { c.drawCircle(x-s*.55f,y+s*.55f,s*.32f,line);c.drawCircle(x+s*.55f,y+s*.55f,s*.32f,line);c.drawLine(x-s*.3f,y+s*.25f,x+s*.75f,y-s,line);c.drawLine(x+s*.3f,y+s*.25f,x-s*.75f,y-s,line) }
            ToolIcon.MORE -> { c.drawCircle(x-s*.65f,y,s*.15f,fill);c.drawCircle(x,y,s*.15f,fill);c.drawCircle(x+s*.65f,y,s*.15f,fill) }
        }
    }
}
