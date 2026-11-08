package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.DesktopGrid
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Native persistence and dragging, with a read-only layout preview before confirmation. */
internal object DesktopDesignerHook {
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor {task ->
        Thread(task,"CherryPick-grid-preview").apply {isDaemon=true}
    }
    fun install(loader:ClassLoader) {
        val launcher=XposedHelpers.findClassIfExists("com.android.launcher.Launcher",loader) ?: return
        DesktopMigration.install(loader)
        XposedBridge.hookAllMethods(launcher,"onResume",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                val activity=param.thisObject as? Activity ?: return
                try {
                    val pending=activity.contentResolver.call(SettingsProvider.CONTENT_URI,
                        SettingsProvider.METHOD_CONSUME_DESKTOP,null,null)?.getBoolean("pending")==true
                    if(pending)activity.window.decorView.post {
                        if(!activity.isFinishing && !activity.isDestroyed)try {show(activity)}
                        catch(t:Throwable){GlassLogger.e("DesktopDesigner","Cannot show desktop menu",t)}
                    }
                }catch(t:Throwable){GlassLogger.e("DesktopDesigner","Cannot open desktop menu",t)}
            }
        })
    }
    private fun show(activity:Activity) {
        val strings=activity.createPackageContext("com.cosmod.launcherglass",Context.CONTEXT_IGNORE_SECURITY)
        fun s(id:Int)=strings.getString(id)
        fun toast(id:Int)=Toast.makeText(activity,s(id),Toast.LENGTH_LONG).show()
        fun error(t:Throwable){GlassLogger.e("DesktopDesigner","Desktop editor failed",t);toast(R.string.ui_desktop_unavailable)}
        val original=DesktopMigration.dimensions(activity)
        val density=activity.resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),0)
            addView(TextView(activity).apply {text=s(R.string.ui_desktop_preview_hint);textSize=14f})
        }
        val controls=LinearLayout(activity)
        fun picker(label:Int,range:IntRange,current:Int):NumberPicker {
            val group=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
            group.addView(TextView(activity).apply {text=s(label);textSize=14f})
            val picker=NumberPicker(activity).apply {
                minValue=range.first;maxValue=range.last;value=current.coerceIn(range);wrapSelectorWheel=false
                descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS;contentDescription=s(label)
            }
            group.addView(picker,LinearLayout.LayoutParams(dp(88),dp(96)))
            controls.addView(group,LinearLayout.LayoutParams(0,-2,1f))
            return picker
        }
        val columns=picker(R.string.ui_desktop_columns,DesktopGrid.columns,original.first)
        val rows=picker(R.string.ui_desktop_rows,DesktopGrid.rows,original.second)
        content.addView(controls)
        val grid=DesktopGridPreview(activity)
        content.addView(grid,LinearLayout.LayoutParams(-1,dp(170)))
        val pager=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        val previous=Button(activity).apply {text="‹";textSize=24f;contentDescription=s(R.string.ui_desktop_previous)}
        val next=Button(activity).apply {text="›";textSize=24f;contentDescription=s(R.string.ui_desktop_next)}
        val page=TextView(activity).apply {gravity=Gravity.CENTER;textSize=14f}
        pager.addView(previous,LinearLayout.LayoutParams(dp(48),dp(48)))
        pager.addView(page,LinearLayout.LayoutParams(0,-2,1f))
        pager.addView(next,LinearLayout.LayoutParams(dp(48),dp(48)))
        content.addView(pager)
        val status=TextView(activity).apply {textSize=14f;setPadding(0,dp(8),0,dp(8));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        content.addView(status)
        val arrange=Button(activity).apply {text=s(R.string.ui_desktop_arrange);isAllCaps=false;textSize=14f;minHeight=dp(48)}
        content.addView(arrange,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(activity).apply {addView(content)}
        val dialog=AlertDialog.Builder(activity).setTitle(s(R.string.ui_desktop_title)).setView(scroll)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(s(R.string.ui_desktop_apply),null).create()
        var preview:DesktopMigration.Preview?=null
        var selectedPage=0
        var generation=0
        var queued:Runnable?=null
        fun showPage() {
            val pages=preview?.pages.orEmpty()
            selectedPage=selectedPage.coerceIn(0,(pages.size-1).coerceAtLeast(0))
            grid.columns=columns.value;grid.rows=rows.value
            grid.cells=pages.getOrNull(selectedPage).orEmpty();grid.invalidate()
            page.text=if(pages.isEmpty())"—" else "${selectedPage+1} / ${pages.size}"
            previous.isEnabled=selectedPage>0
            next.isEnabled=selectedPage<pages.size-1
        }
        fun refreshPreview() {
            val token=++generation
            val x=columns.value;val y=rows.value
            preview=null;showPage()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            status.text=s(R.string.ui_desktop_checking)
            queued?.let {activity.window.decorView.removeCallbacks(it)}
            val work=Runnable {
                executor.execute {
                    try {
                        val result=DesktopMigration.preview(activity,x,y)
                        activity.runOnUiThread {
                            if(token!=generation || activity.isFinishing || activity.isDestroyed || !dialog.isShowing)return@runOnUiThread
                            preview=result;showPage()
                            status.text=if(result.safe)strings.getString(R.string.ui_desktop_preview_summary,x,y,result.pages.size)
                                else s(R.string.ui_desktop_no_space)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=result.safe && (x to y)!=original
                        }
                    }catch(t:Throwable){
                        GlassLogger.e("DesktopDesigner","Cannot calculate layout preview",t)
                        activity.runOnUiThread {
                            if(token==generation && !activity.isFinishing && !activity.isDestroyed && dialog.isShowing)
                                status.text=s(R.string.ui_desktop_unavailable)
                        }
                    }
                }
            }
            queued=work
            activity.window.decorView.postDelayed(work,180L)
        }
        previous.setOnClickListener {selectedPage--;showPage()}
        next.setOnClickListener {selectedPage++;showPage()}
        columns.setOnValueChangedListener {_,_,_ -> refreshPreview()}
        rows.setOnValueChangedListener {_,_,_ -> refreshPreview()}
        arrange.setOnClickListener {
            try {dialog.dismiss();DesktopMigration.arrange(activity)}catch(t:Throwable){error(t)}
        }
        dialog.setOnDismissListener {
            generation++
            queued?.let {activity.window.decorView.removeCallbacks(it)}
        }
        dialog.setOnShowListener {
            for(button in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE))
                dialog.getButton(button).apply {isAllCaps=false;textSize=14f}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val x=columns.value;val y=rows.value
                if(preview?.safe!=true || (x to y)==original)return@setOnClickListener
                AlertDialog.Builder(activity).setTitle("$x × $y").setMessage(s(R.string.ui_desktop_apply_warning))
                    .setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(s(R.string.ui_desktop_apply)){_,_ ->
                        if(DesktopMigration.busy() || DesktopMigration.loading(activity) || DesktopMigration.dimensions(activity)!=original) {
                            toast(R.string.ui_desktop_busy)
                        }else {
                            val progress=AlertDialog.Builder(activity).setMessage(s(R.string.ui_desktop_applying)).setCancelable(false).create()
                            try {
                                dialog.dismiss();progress.show()
                                DesktopMigration.start(activity,x,y){success ->
                                    progress.dismiss()
                                    if(success) {
                                        toast(R.string.ui_desktop_applied)
                                        try {DesktopMigration.arrange(activity)}catch(t:Throwable){error(t)}
                                    }else {
                                        toast(R.string.ui_desktop_failed)
                                        // Native AsyncTask changes transient CellLayouts before its transaction.
                                        activity.recreate()
                                    }
                                }
                            }catch(t:Throwable){progress.dismiss();error(t);activity.recreate()}
                        }
                    }.show()
            }
            refreshPreview()
        }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(1),-2)
        GlassLogger.i("DesktopDesigner","Menu opened; current grid ${original.first}x${original.second}")
    }
}
