package luxe.texture3d.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class FloatingResourceBrowserPanel(
    context:Context,
    private val actions:ResourceBrowserActions?=null
):FrameLayout(context){
    private val prefs=context.getSharedPreferences("resource_browser_panel",Context.MODE_PRIVATE)
    private val titleHeight=dp(42);private val minimumWidth=dp(430);private val minimumHeight=dp(260)
    private val content=ResourceBrowserView(context,{hidePanel()},false,actions)
    private val resizeHandle=TextView(context)
    private var minimized=false;private var maximized=false;private var restoreX=0f;private var restoreY=0f;private var restoreWidth=0;private var restoreHeight=0

    init{
        isClickable=true;isFocusable=true;elevation=dp(14).toFloat();setBackgroundResource(R.drawable.hub_dialog_bg)
        val title=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(11),0,dp(5),0);setBackgroundColor(0xff202020.toInt())}
        title.addView(label("RESOURCE BROWSER",13f).apply{setTypeface(typeface,1)},LinearLayout.LayoutParams(0,-1,1f))
        title.addView(control("—"){toggleMinimize()},LinearLayout.LayoutParams(dp(38),-1));title.addView(control("□"){toggleMaximize()},LinearLayout.LayoutParams(dp(38),-1));title.addView(control("×"){hidePanel()},LinearLayout.LayoutParams(dp(38),-1))
        addView(title,LayoutParams(-1,titleHeight,Gravity.TOP));addView(content,LayoutParams(-1,-1).apply{topMargin=titleHeight})
        resizeHandle.text="◢";resizeHandle.textSize=17f;resizeHandle.setTextColor(0xff9aa9bc.toInt());resizeHandle.gravity=Gravity.CENTER;resizeHandle.setBackgroundColor(0xff252525.toInt());addView(resizeHandle,LayoutParams(dp(28),dp(28),Gravity.END or Gravity.BOTTOM))
        installDrag(title);installResize();post{restoreOrDefault()}
    }

    override fun onTouchEvent(event:MotionEvent)=true
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{parent?.requestDisallowInterceptTouchEvent(true);return super.dispatchTouchEvent(event)}

    fun showPanel(){visibility=VISIBLE;bringToFront();if(width==0)post{restoreOrDefault()}}
    fun hidePanel(){saveState();visibility=GONE}
    fun togglePanel(){if(visibility==VISIBLE)hidePanel()else showPanel()}

    private fun installDrag(title:View){var downX=0f;var downY=0f;var startX=0f;var startY=0f;title.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;startX=x;startY=y;true};MotionEvent.ACTION_MOVE->{if(!maximized){x=startX+event.rawX-downX;y=startY+event.rawY-downY;clampToParent()};true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{saveState();true};else->false}}}
    private fun installResize(){var downX=0f;var downY=0f;var startW=0;var startH=0;resizeHandle.setOnTouchListener{_,event->when(event.actionMasked){MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;startW=width;startH=height;true};MotionEvent.ACTION_MOVE->{if(!maximized&&!minimized){val parentView=parent as? View;val maxW=(parentView?.width?:resources.displayMetrics.widthPixels)-x.toInt();val maxH=(parentView?.height?:resources.displayMetrics.heightPixels)-y.toInt();val lp=layoutParams;lp.width=(startW+event.rawX-downX).roundToInt().coerceIn(minimumWidth.coerceAtMost(maxW),maxW.coerceAtLeast(minimumWidth));lp.height=(startH+event.rawY-downY).roundToInt().coerceIn(minimumHeight.coerceAtMost(maxH),maxH.coerceAtLeast(minimumHeight));layoutParams=lp};true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{saveState();true};else->false}}}

    private fun toggleMinimize(){if(maximized)toggleMaximize();minimized=!minimized;if(minimized){restoreHeight=height;content.visibility=GONE;resizeHandle.visibility=GONE;layoutParams=layoutParams.apply{height=titleHeight}}else{content.visibility=VISIBLE;resizeHandle.visibility=VISIBLE;layoutParams=layoutParams.apply{height=restoreHeight.coerceAtLeast(minimumHeight)}};saveState()}
    private fun toggleMaximize(){val parentView=parent as? View?:return;if(!maximized){restoreX=x;restoreY=y;restoreWidth=width;restoreHeight=height;maximized=true;minimized=false;content.visibility=VISIBLE;resizeHandle.visibility=GONE;x=0f;y=0f;layoutParams=layoutParams.apply{width=parentView.width;height=parentView.height}}else{maximized=false;resizeHandle.visibility=VISIBLE;x=restoreX;y=restoreY;layoutParams=layoutParams.apply{width=restoreWidth.coerceAtLeast(minimumWidth);height=restoreHeight.coerceAtLeast(minimumHeight)};clampToParent()};saveState()}

    private fun restoreOrDefault(){val parentView=parent as? View?:return;val compact=resources.configuration.screenWidthDp<840;val saved=prefs.contains("w");if(saved){val pw=parentView.width.toFloat();val ph=parentView.height.toFloat();val lp=layoutParams;lp.width=(prefs.getFloat("w",.78f)*pw).roundToInt().coerceIn(minimumWidth.coerceAtMost(parentView.width),parentView.width);lp.height=(prefs.getFloat("h",.78f)*ph).roundToInt().coerceIn(minimumHeight.coerceAtMost(parentView.height),parentView.height);layoutParams=lp;x=prefs.getFloat("x",.11f)*pw;y=prefs.getFloat("y",.10f)*ph;minimized=prefs.getBoolean("min",false);maximized=prefs.getBoolean("max",false)}else{val lp=layoutParams;lp.width=(parentView.width*.80f).roundToInt();lp.height=(parentView.height*.82f).roundToInt();layoutParams=lp;x=parentView.width*.10f;y=parentView.height*.09f}
        if(compact||maximized){maximized=false;toggleMaximize()}else if(minimized){minimized=false;toggleMinimize()}else clampToParent()}
    private fun clampToParent(){val p=parent as? View?:return;x=x.coerceIn(0f,(p.width-width).coerceAtLeast(0).toFloat());y=y.coerceIn(0f,(p.height-height).coerceAtLeast(0).toFloat())}
    private fun saveState(){val p=parent as? View?:return;if(p.width<=0||p.height<=0)return;prefs.edit().putFloat("x",x/p.width).putFloat("y",y/p.height).putFloat("w",width.toFloat()/p.width).putFloat("h",height.toFloat()/p.height).putBoolean("min",minimized).putBoolean("max",maximized).apply()}
    private fun control(value:String,click:()->Unit)=label(value,18f).apply{gravity=Gravity.CENTER;setOnClickListener{click()}}
    private fun label(value:String,size:Float)=TextView(context).apply{text=value;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
}
