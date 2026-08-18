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
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/** Phase 5 context-aware WIP editor structure. No MeshLibs operations are connected. */
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
    private val menu = LinearLayout(context)
    private val contextBar = LinearLayout(context)
    private var activeWorkspace = "Modeling"
    private var component = "Face"
    private var activeTool = 1
    private val workspaceViews = linkedMapOf<String, TextView>()

    init {
        clipChildren = false
        addView(buildPrimaryHeader(), LayoutParams(-1, px(40), Gravity.TOP))
        addView(buildContextHeader(), LayoutParams(-1, px(37), Gravity.TOP).apply { topMargin = px(40) })
        addView(buildToolRail(), LayoutParams(px(47), -1, Gravity.TOP or Gravity.START).apply { topMargin = px(77); bottomMargin = px(25) })
        addView(buildViewportState(), LayoutParams(px(350), px(48), Gravity.TOP or Gravity.START).apply { leftMargin = px(59); topMargin = px(89) })
        addView(buildStatusBar(), LayoutParams(-1, px(25), Gravity.BOTTOM))
        menu.orientation = LinearLayout.VERTICAL
        menu.setPadding(px(7), px(6), px(7), px(6))
        menu.background = bg(0xff1a1e22.toInt(), 0xff46515a.toInt(), 2)
        menu.elevation = px(14).toFloat(); menu.visibility = GONE
        addView(menu, LayoutParams(px(205), -2, Gravity.TOP or Gravity.START).apply { topMargin = px(38) })
    }

    private fun buildPrimaryHeader(): View {
        val row = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(px(5),0,px(7),0); background=bg(0xff0e1113.toInt(),0xff30363c.toInt(),0) }
        row.addView(LuxeToolIconView(context, ToolIcon.LOGO, Color.WHITE, false), LinearLayout.LayoutParams(px(36),px(36)))
        listOf("File","Edit","Render").forEach { name -> row.addView(topItem(name, true), LinearLayout.LayoutParams(-2,px(32))) }
        row.addView(divider(), LinearLayout.LayoutParams(px(1),px(21)).apply { setMargins(px(7),0,px(7),0) })
        val workspaces=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        listOf("Modeling","Sculpt","T/V","UVs","Animation","Nodes").forEach { name ->
            val v=topItem(name,false);workspaceViews[name]=v;workspaces.addView(v,LinearLayout.LayoutParams(-2,px(32)))
        }
        workspaces.addView(topItem("＋",false),LinearLayout.LayoutParams(px(35),px(32)))
        row.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;addView(workspaces) },LinearLayout.LayoutParams(0,-1,1f))
        row.addView(chip("Scene  ▾",false){ showMenu("Scene",it) },LinearLayout.LayoutParams(px(84),px(26)).apply{rightMargin=px(5)})
        row.addView(chip("◐  ◉  ◌",false){ showMenu("Viewport",it) },LinearLayout.LayoutParams(px(80),px(26)))
        refreshWorkspaces();return row
    }

    private fun topItem(name:String, global:Boolean)=label(if(global) "$name  ▾" else name,10.5f,text).apply {
        gravity=Gravity.CENTER;setPadding(px(10),0,px(10),0)
        setOnClickListener { if(global) showMenu(name,it) else { activeWorkspace=name;refreshWorkspaces();rebuildContext();Toast.makeText(context,"$name workspace preview",Toast.LENGTH_SHORT).show() } }
    }
    private fun refreshWorkspaces()=workspaceViews.forEach{(name,v)->v.setTextColor(if(name==activeWorkspace)Color.WHITE else text);v.background=if(name==activeWorkspace)bg(0xff20313b.toInt(),blue,1)else null}

    private fun buildContextHeader(): View {
        contextBar.orientation=LinearLayout.HORIZONTAL;contextBar.gravity=Gravity.CENTER_VERTICAL;contextBar.setPadding(px(7),0,px(8),0);contextBar.background=bg(0xff171b1e.toInt(),0xff343a40.toInt(),0)
        rebuildContext();return contextBar
    }
    private fun rebuildContext(){
        contextBar.removeAllViews()
        contextBar.addView(chip("WIP Mode  ▾",true){showMenu("WIP Mode",it)},LinearLayout.LayoutParams(px(105),px(27)).apply{rightMargin=px(5)})
        contextBar.addView(chip("Object  ▾",false){showMenu("Object",it)},LinearLayout.LayoutParams(px(82),px(27)).apply{rightMargin=px(5)})
        listOf("Vertex","Edge","Face").forEach{name->contextBar.addView(chip(name,name==component){component=name;rebuildContext()},LinearLayout.LayoutParams(px(61),px(27)).apply{rightMargin=px(3)})}
        contextBar.addView(divider(),LinearLayout.LayoutParams(px(1),px(21)).apply{setMargins(px(5),0,px(7),0)})
        listOf("Select","Add  ▾","Mesh  ▾").forEach{name->contextBar.addView(chip(name,false){showMenu(name.removeSuffix("  ▾"),it)},LinearLayout.LayoutParams(px(70),px(27)).apply{rightMargin=px(3)})}
        contextBar.addView(divider(),LinearLayout.LayoutParams(px(1),px(21)).apply{setMargins(px(6),0,px(8),0)})
        contextBar.addView(label("Bevel",10f,blue).apply{gravity=Gravity.CENTER_VERTICAL;typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(px(62),-1))
        listOf("Width  0.08","Segments  3","Clamp  ✓").forEach{name->contextBar.addView(chip(name,false){Toast.makeText(context,"$name — UI-only value",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(px(if(name.startsWith("Segments"))96 else 86),px(25)).apply{rightMargin=px(4)})}
        contextBar.addView(View(context),LinearLayout.LayoutParams(0,1,1f));contextBar.addView(label("⌁   ◫",15f,text).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(px(70),-1))
    }

    private fun buildToolRail():View{
        val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.TOP;setPadding(px(3),px(4),px(3),px(4));background=bg(0xf2111416.toInt(),0xff30363c.toInt(),0)}
        val tools=listOf(ToolIcon.SELECT,ToolIcon.BEVEL,ToolIcon.COMPONENTS,ToolIcon.TRANSFORM,ToolIcon.EXTRUDE,ToolIcon.INSET,ToolIcon.CUT,ToolIcon.MORE)
        val names=listOf("Select","Bevel","Component Selection","Transform","Extrude","Inset","Cut","More Tools")
        tools.forEachIndexed{i,icon->column.addView(LuxeToolIconView(context,icon,if(i==activeTool)blue else text,i==activeTool).apply{contentDescription=names[i];setOnClickListener{activeTool=i;Toast.makeText(context,"${names[i]} — structure only",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(px(40),px(40)).apply{bottomMargin=px(3)})}
        return ScrollView(context).apply{isVerticalScrollBarEnabled=true;scrollBarStyle=View.SCROLLBARS_INSIDE_OVERLAY;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;addView(column,ScrollView.LayoutParams(-1,-2))}
    }

    private fun buildViewportState()=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL;setPadding(px(4),0,0,0)
        addView(label("User Perspective",10f,0xffc8cdd2.toInt()),LinearLayout.LayoutParams(-1,px(21)))
        addView(label("Building_A  •  $component Selection  •  Bevel",9.5f,muted),LinearLayout.LayoutParams(-1,px(21)))
    }
    private fun buildStatusBar():View{val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(px(9),0,px(9),0);background=bg(0xf50e1113.toInt(),0xff30363c.toInt(),0)};row.addView(label("Select  •  Orbit  •  Pan  •  Zoom",9f,muted),LinearLayout.LayoutParams(0,-1,1f));row.addView(label("Faces 518  •  Tris 27.1K  •  WIP Matcap",9f,muted),LinearLayout.LayoutParams(-2,-1));return row}

    private fun showMenu(section:String,anchor:View){
        menu.removeAllViews();menu.addView(label(section.uppercase(),9f,blue).apply{typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER_VERTICAL;setPadding(px(8),0,0,0)},LinearLayout.LayoutParams(-1,px(26)))
        val entries=when(section){"File"->listOf("Open Resource Browser","Save Project","Recent Files —","Import —");"WIP Mode"->listOf("WIP Mode","Object Mode —","Edit Mode —","Sculpt Mode —");else->listOf("Context options —","Presets —","Customize —")}
        entries.forEach{name->menu.addView(label(name,10f,if(name.endsWith("—"))muted else text).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(px(9),0,px(6),0);background=bg(0xff20252a.toInt(),0x00343b42,2);setOnClickListener{menu.visibility=GONE;when(name){"Open Resource Browser"->onFiles();"Save Project"->onSave();else->Toast.makeText(context,"$name placeholder",Toast.LENGTH_SHORT).show()}}},LinearLayout.LayoutParams(-1,px(30)).apply{topMargin=px(3)})}
        val pos=IntArray(2);anchor.getLocationOnScreen(pos);(menu.layoutParams as LayoutParams).leftMargin=pos[0].coerceAtMost(width-px(212)).coerceAtLeast(0);menu.visibility=VISIBLE;menu.bringToFront()
    }
    private fun chip(value:String,active:Boolean,click:(View)->Unit)=label(value,9.5f,if(active)blue else text).apply{gravity=Gravity.CENTER;background=bg(if(active)0xff20323c.toInt() else 0xff202428.toInt(),if(active)0xff4f8daf.toInt() else 0xff363d43.toInt(),2);setOnClickListener{click(it)}}
    private fun divider()=View(context).apply{setBackgroundColor(0xff394047.toInt())}
    private fun label(value:String,size:Float,color:Int)=TextView(context).apply{text=value;textSize=size*scale.coerceAtMost(1.15f);setTextColor(color);includeFontPadding=false;maxLines=1}
    private fun bg(fill:Int,stroke:Int,radius:Int)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);if(stroke ushr 24!=0)setStroke(px(1),stroke);cornerRadius=px(radius).toFloat()}
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
