package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.SuUtil
import com.cosmod.launcherglass.util.LsposedScopeStatus
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var page: FrameLayout
    private val executor = Executors.newSingleThreadExecutor()
    private data class Gate(val view: View, val allowed: () -> Boolean)
    private val gates = mutableListOf<Gate>()
    private val resets = mutableListOf<Pair<View, List<String>>>()
    private var currentScroll: androidx.core.widget.NestedScrollView? = null
    private var settingsPage = false
    private var folderExpanded=true
    private var blurExpanded = true
    private var designExpanded = false
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var lsposedText:TextView?=null
    private var statusHint:TextView?=null
    private var fluxWarning:View?=null
    private val supportListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key==SettingsProvider.KEY_NATIVE_FLUX_SUPPORT)runOnUiThread {updateFluxWarning()}
    }
    private var scopeState:LsposedScopeStatus.State?=null
    private var statusEpoch=0
    private var restartBusy = false
    private var updatingUi = false
    private val refreshers = mutableListOf<() -> Unit>()
    private val toggleViews = mutableListOf<Pair<SquareAction,String>>()
    private val cancelHolds = mutableListOf<() -> Unit>()
    private val primary get() = color(M.attr.colorPrimary)
    private val onSurface get() = color(M.attr.colorOnSurface)
    private val secondaryText get() = color(M.attr.colorOnSurfaceVariant)
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun bool(key:String):Boolean = if(key.startsWith("folder_") && key.removePrefix("folder_") in FolderSettingsKeys.booleans)
        prefs.getBoolean(key,prefs.getBoolean(key.removePrefix("folder_"),SettingsDefaults.boolean(key)))else prefs.getBoolean(key,SettingsDefaults.boolean(key))
    private fun number(key:String):Int = if(key.startsWith("folder_") && key.removePrefix("folder_") in FolderSettingsKeys.integers)
        prefs.getInt(key,prefs.getInt(key.removePrefix("folder_"),SettingsDefaults.integer(key)))else prefs.getInt(key,SettingsDefaults.integer(key))
    private fun darkIcons() = prefs.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, com.cosmod.launcherglass.util.IconPalette.isSystemDark(this))
    private fun master() = bool(SettingsProvider.KEY_ENABLED)
    private fun blur() = master() && bool(SettingsProvider.KEY_BLUR_ENABLED)
    private fun folderOwn()=blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)
    private fun design() = master() && bool(SettingsProvider.KEY_DESIGN_ENABLED)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiLanguage.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(supportListener)
        SettingsDefaults.initialize(this, prefs)
        settingsPage = savedInstanceState?.getBoolean("settingsPage") ?: false
        blurExpanded = savedInstanceState?.getBoolean("blurExpanded") ?: true
        designExpanded = savedInstanceState?.getBoolean("designExpanded") ?: false
        root = column().apply { setBackgroundColor(color(M.attr.colorSurface)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        page = FrameLayout(this)
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
        showPage(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPage) { settingsPage = false; showPage() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if(::prefs.isInitialized) {
            refreshControls()
            updateStatus()
            val epoch=++statusEpoch
            executor.execute {
                val state=LsposedScopeStatus.read(this)
                runOnUiThread { if(epoch==statusEpoch && !isDestroyed) { scopeState=state;updateStatus() } }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settingsPage", settingsPage)
        outState.putBoolean("blurExpanded", blurExpanded)
        outState.putBoolean("designExpanded", designExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { cancelHolds.toList().forEach { it() };super.onPause() }
    override fun onDestroy() { if(::prefs.isInitialized)prefs.unregisterOnSharedPreferenceChangeListener(supportListener);executor.shutdown(); super.onDestroy() }
    private fun refreshControls() {
        updatingUi = true
        try {
            toggleViews.forEach { (button,key) ->
                button.isChecked=bool(key)
                button.glyph.imageTintList=ColorStateList.valueOf(color(if(bool(key))M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))
                button.backgroundTintList=ColorStateList.valueOf(color(if(bool(key)) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh))
            }
            refreshers.forEach { it() }
        } finally { updatingUi=false }
        updateGates()
    }
    private val reveals=java.util.WeakHashMap<View,ValueAnimator>()
    private fun reveal(view:View,open:Boolean) {
        reveals.remove(view)?.cancel()
        val from=if(view.visibility==View.GONE)0 else view.height
        val width=(view.parent as View).width.coerceAtLeast(1)
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val to=if(open)view.measuredHeight else 0
        view.visibility=View.VISIBLE
        view.layoutParams=view.layoutParams.apply { height=from }
        val animator=ValueAnimator.ofInt(from,to).apply {
            duration=330;interpolator=CherryMotion
            addUpdateListener { view.layoutParams=view.layoutParams.apply { height=(it.animatedValue as Int).coerceAtLeast(0) } }
            addListener(object:android.animation.AnimatorListenerAdapter(){
                private var canceled=false
                override fun onAnimationCancel(a:android.animation.Animator){canceled=true}
                override fun onAnimationEnd(a:android.animation.Animator){if(!canceled){view.visibility=if(open)View.VISIBLE else View.GONE;view.layoutParams=view.layoutParams.apply {height=ViewGroup.LayoutParams.WRAP_CONTENT};reveals.remove(view)}}
            })
        }
        reveals[view]=animator;animator.start()
    }
    private fun layoutTransition(parent: ViewGroup, durationMs:Long=264) {
        TransitionManager.beginDelayedTransition(parent,AutoTransition().apply {
            duration=durationMs;interpolator=CherryMotion
            // The action strip owns its widths and glyphs. ChangeBounds must
            // never capture and animate the same cells a second time.
            excludeTarget(ConnectedActions::class.java,true)
            excludeChildren(ConnectedActions::class.java,true)
        })
    }
    private fun pulse(view:View,time:Long=1000) { (view.parent as? ConnectedActions)?.pulse(view,time) }


    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 16f, muted: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if (muted) secondaryText else onSurface)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun haptic(view: View, tick: Boolean = false) {
        view.performHapticFeedback(if (tick) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM)
    }
    private fun card(content: View, inner: Boolean = false): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(if (inner) 20 else 28).toFloat()
        cardElevation = 0f; strokeWidth = 0
        setCardBackgroundColor(color(if (inner) M.attr.colorSurfaceContainerHigh else M.attr.colorSurfaceContainerLow))
        addView(content)
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(color(M.attr.colorOutlineVariant)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(20); rightMargin = dp(20) })
    }
    private fun gate(view: View, allowed: () -> Boolean) { gates.add(Gate(view, allowed)) }
    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i), enabled)
    }
    private fun updateGates() {
        gates.forEach { enableTree(it.view, it.allowed()) }
        gates.forEach {
            var ancestor = it.view.parent as? View
            var dimmedParent = false
            while (ancestor != null && ancestor !== page) {
                if (!ancestor.isEnabled) { dimmedParent = true; break }
                ancestor = ancestor.parent as? View
            }
            it.view.alpha = if (!it.allowed() && !dimmedParent) .38f else 1f
        }
        resets.forEach { (view, keys) ->
            var ancestor = view.parent as? View
            var allowed = true
            while (ancestor != null && ancestor !== page) {
                val node = ancestor ?: break
                if (!node.isEnabled) { allowed = false; break }
                ancestor = node.parent as? View
            }
            val changed = keys.any { key ->
                val default = SettingsDefaults.value(key)
                if (default is Boolean) bool(key) != default else number(key) != default
            }
            view.isEnabled = allowed && changed
            view.alpha = if (allowed && !changed) .65f else 1f
        }
    }
    private fun save(key: String, value: Any) {
        if(updatingUi)return
        prefs.edit().apply {
            if(key==FolderSettingsKeys.OWN && value==true){
                FolderSettingsKeys.integers.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putInt(own,number(base))}
                FolderSettingsKeys.booleans.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putBoolean(own,bool(base))}
            }
            when(value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) } }.apply()
        contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
        updateGates();updateStatus()
        if(key==FolderSettingsKeys.OWN || key==SettingsProvider.KEY_DEVELOPER_MODE)refreshControls()
    }

    private fun iconButton(icon: Int, label: String, filled: Boolean = false, action: (View) -> Unit): View {
        return AppCompatImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon); contentDescription = label
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = ColorStateList.valueOf(color(if (filled) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))
            val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(if (filled) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh)) }
            background = RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)), shape, null)
            setOnTouchListener { view, event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(143).setInterpolator(CherryMotion).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setInterpolator(CherryMotion).setDuration(242).start()
                }
                false
            }
            setOnClickListener { haptic(it); action(it) }
        }
    }

    private fun showPage(animate: Boolean = true) {
        cancelHolds.toList().forEach { it() };cancelHolds.clear()
        gates.clear(); resets.clear();refreshers.clear();toggleViews.clear();statusText = null; statusDot = null
        page.removeAllViews()
        val shell = column()
        val header = row().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        if (settingsPage) header.addView(iconButton(R.drawable.ic_arrow_back,getString(R.string.ui_back)) { settingsPage=false;showPage() })
        header.addView(text("CherryPick Icons",21f,bold=true).apply {
            gravity=Gravity.START;maxLines=1;setPadding(if(settingsPage)dp(12) else 0,0,dp(12),0)
        },LinearLayout.LayoutParams(0,-2,1f))
        if(settingsPage) header.addView(iconButton(R.drawable.ic_language,getString(R.string.ui_language)) { view ->
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_language))
                .setSingleChoiceItems(arrayOf("Русский","English"),if(UiLanguage.tag(this)=="ru")0 else 1) { dialog,index ->
                    haptic(view);UiLanguage.choose(this,if(index==0)"ru" else "en");dialog.dismiss();recreate()
                }.setNegativeButton(getString(R.string.ui_close),null).show()
        })
        if (!settingsPage) header.addView(iconButton(R.drawable.ic_settings,getString(R.string.ui_settings)) { settingsPage=true;showPage() })
        add(shell, header)
        val scroll = androidx.core.widget.NestedScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        currentScroll = scroll
        val body = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(body, ViewGroup.LayoutParams(-1,-2))
        shell.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        if (settingsPage) buildSettings(body) else buildHome(body)
        page.addView(shell, FrameLayout.LayoutParams(-1,-1))
        if (!settingsPage) {
            // A floating button protects only its own bounds; content continues
            // behind it instead of reserving a full-width bottom strip.
            body.setPadding(dp(20),dp(12),dp(20),dp(108))
            page.addView(restartButton(),FrameLayout.LayoutParams(dp(64),dp(64),Gravity.BOTTOM or Gravity.START).apply {
                marginStart=dp(24);bottomMargin=dp(20)
            })
        }
        updateGates(); updateStatus()
        if (animate) { shell.alpha = 0f; shell.translationY = dp(8).toFloat(); shell.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(CherryMotion).start() }
    }

    private fun buildHome(body: LinearLayout) {
        val status = row().apply { setPadding(dp(20),dp(20),dp(20),dp(20)) }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) } }
        status.addView(dot, LinearLayout.LayoutParams(dp(10),dp(10)).apply { rightMargin = dp(12) }); statusDot = dot
        val statusLabels = column()
        lsposedText=text("LSPosed",12f,true)
        add(statusLabels,lsposedText!!)
        statusText=text(getString(R.string.ui_cherrypick_disabled),17f,bold=true)
        add(statusLabels,statusText!!,5)
        statusHint=text("",12f,true).apply { visibility=View.GONE }
        add(statusLabels,statusHint!!,6)
        status.addView(statusLabels,LinearLayout.LayoutParams(0,-2,1f))
        status.addView(ConnectedActions(this,listOf(resetButton(SettingsProvider.KEY_ENABLED),makeSwitch(getString(R.string.ui_module),SettingsProvider.KEY_ENABLED))))
        add(body,card(status),bottom=24)
        val warning=column().apply {setPadding(dp(20),dp(16),dp(20),dp(16))}
        add(warning,text(getString(R.string.ui_flux_only_title),16f,bold=true).apply {
            setTextColor(color(M.attr.colorOnTertiaryContainer))
        })
        add(warning,text(getString(R.string.ui_flux_only_warning),14f).apply {
            setTextColor(color(M.attr.colorOnTertiaryContainer))
        },6)
        val warningCard=card(warning).apply {setCardBackgroundColor(color(M.attr.colorTertiaryContainer))}
        fluxWarning=warningCard
        add(body,warningCard,bottom=16)
        updateFluxWarning()
        coloringSection(body)
        section(body, getString(R.string.ui_background_blur), "", SettingsProvider.KEY_BLUR_ENABLED, blurExpanded,
            { blurExpanded = it }, ::master, ::blur) { content ->
            blurStyleSelector(content)
            effectGroup(content,getString(R.string.ui_blur),SettingsProvider.KEY_BLUR_RADIUS_ENABLED,
                getString(R.string.ui_blur_radius),SettingsProvider.KEY_BLUR_RADIUS,max=50,allowed=::blur,min=0,suffix="") { details ->
                if(bool(SettingsProvider.KEY_DEVELOPER_MODE)) {
                    val quality=slider(getString(R.string.ui_blur_quality),SettingsProvider.KEY_BLUR_QUALITY,0,100,"%",getString(R.string.ui_blur_quality_hint))
                    gate(quality,::blur);add(details,quality)
                }
                val fps=slider(getString(R.string.ui_live_blur_fps),SettingsProvider.KEY_LIVE_FPS,1,60," FPS")
                gate(fps) { blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE }
                add(details,fps)
            }
            blurToneGroup(content)
            val locations=column()
            val locationDetails=column().apply { visibility=View.GONE }
            divider(locationDetails)
            add(locationDetails,switchRow(getString(R.string.ui_drawer_blur),SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            add(locationDetails,switchRow(getString(R.string.ui_folder_blur),SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,getString(R.string.ui_folder_blur_description)))
            gate(locationDetails) { blur() && bool(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED) }
            add(locations,switchRow(getString(R.string.ui_icon_blur),SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,expanded=false) { open ->
                reveal(locationDetails,open)
            })
            add(locations,locationDetails)
            add(content,card(locations,true),top=8,bottom=4)
        }
        section(body, getString(R.string.ui_icon_design), getString(R.string.ui_icon_design_hint), SettingsProvider.KEY_DESIGN_ENABLED, designExpanded,
            { designExpanded = it }, ::master, ::design) { content ->
            effectGroup(content, getString(R.string.ui_icon_contour), SettingsProvider.KEY_ICON_STROKE,
                getString(R.string.ui_contour_opacity), SettingsProvider.KEY_STROKE_OPACITY)
            glyphOutlineGroup(content)
            effectGroup(content, getString(R.string.ui_logo_shadow), SettingsProvider.KEY_GLYPH_SHADOW,
                getString(R.string.ui_logo_shadow_opacity), SettingsProvider.KEY_SHADOW_OPACITY)
            effectGroup(content, getString(R.string.ui_icon_depth), SettingsProvider.KEY_VOLUME_ENABLED,
                getString(R.string.ui_depth_strength), SettingsProvider.KEY_GRADIENT_OPACITY)
        }
        folderSection(body)
        add(body,text(getString(R.string.ui_widgets),22f,bold=true),top=24,bottom=8)
        effectGroup(body,getString(R.string.ui_photo_widget_blur),SettingsProvider.KEY_PHOTO_WIDGET_BLUR,
            getString(R.string.ui_photo_widget_opacity),SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,min=0,max=100,
            allowed={master() && blur()},suffix="%") {details ->
            divider(details)
            val tint=switchRow(getString(R.string.ui_photo_widget_tint),SettingsProvider.KEY_PHOTO_WIDGET_TINT,getString(R.string.ui_photo_widget_tint_hint))
            gate(tint){master() && blur() && bool(SettingsProvider.KEY_PHOTO_WIDGET_BLUR)}
            add(details,tint)
            val liquid=column()
            if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(liquid,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,0,100,"%"))
            add(liquid,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,0,300,"%"))
            gate(liquid){blur() && bool(SettingsProvider.KEY_PHOTO_WIDGET_BLUR) && number(SettingsProvider.KEY_BLUR_STYLE)==1}
            liquid.visibility=if(number(SettingsProvider.KEY_BLUR_STYLE)==1)View.VISIBLE else View.GONE
            refreshers.add {liquid.visibility=if(number(SettingsProvider.KEY_BLUR_STYLE)==1)View.VISIBLE else View.GONE}
            add(details,liquid)
        }
    }

    private fun folderSection(body:LinearLayout){
        section(body,getString(R.string.ui_folders),"",SettingsProvider.KEY_FOLDER_BLUR_ENABLED,folderExpanded,
            {folderExpanded=it},::blur,{blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}) {content ->
            add(content,switchRow(getString(R.string.ui_folder_own),FolderSettingsKeys.OWN,getString(R.string.ui_folder_mode_hint)))
            val mode=text(getString(if(bool(FolderSettingsKeys.OWN))R.string.ui_folder_mode_own else R.string.ui_folder_mode_icons),14f,true)
            add(content,mode,bottom=10)
            val controls=column().apply {visibility=if(bool(FolderSettingsKeys.OWN))View.VISIBLE else View.GONE}
            refreshers.add {
                controls.visibility=if(bool(FolderSettingsKeys.OWN))View.VISIBLE else View.GONE
                mode.text=getString(if(bool(FolderSettingsKeys.OWN))R.string.ui_folder_mode_own else R.string.ui_folder_mode_icons)
            }
            gate(controls){blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)}
            folderStyleSelector(controls)
            effectGroup(controls,getString(R.string.ui_blur),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED),
                getString(R.string.ui_blur_radius),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS),max=50,allowed=::folderOwn,suffix="") {details ->
                if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(details,slider(getString(R.string.ui_blur_quality),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_QUALITY),0,100,"%"))
            }
            val tone=column()
            val details=column().apply {visibility=View.GONE}
            add(tone,switchRow(getString(R.string.ui_blur_tone),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED),expanded=false){open -> reveal(details,open)})
            gate(details){folderOwn() && bool(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED))}
            for((label,key) in listOf(R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
                R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
                R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
                R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)){
                divider(details)
                add(details,slider(getString(label),FolderSettingsKeys.key(key),0,if(key==SettingsProvider.KEY_BLUR_SATURATION || key==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",
                    if(key==SettingsProvider.KEY_BLUR_SATURATION)getString(R.string.ui_blur_saturation_hint)else null))
            }
            add(tone,details);add(controls,card(tone,true),top=8,bottom=4)
            add(content,controls)
        }
    }

    private fun folderStyleSelector(parent:LinearLayout) {
        val title=row()
        title.addView(text(getString(R.string.ui_blur_style),16f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        add(parent,title,bottom=4)
        val group=com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection=true;isSelectionRequired=true
        }
        val buttons=listOf("Frosted","Liquid Glass").map { label ->
            com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                id=View.generateViewId();text=label;isAllCaps=false;textSize=14f;cornerRadius=dp(12)
                insetTop=0;insetBottom=0;minWidth=0;minimumWidth=0
                group.addView(this,LinearLayout.LayoutParams(0,dp(48),1f))
            }
        }
        var refreshing=false
        fun refresh(){refreshing=true;group.check(buttons[number(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE)).coerceIn(0,1)].id);refreshing=false}
        refresh()
        group.addOnButtonCheckedListener { _,id,checked ->
            if(checked && !refreshing) {
                haptic(group);save(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE),buttons.indexOfFirst {it.id==id})
            }
        }
        gate(group,::folderOwn)
        gate(buttons[1]) {folderOwn() && android.os.Build.VERSION.SDK_INT>=33}
        refreshers.add {refresh()}
        add(parent,group,bottom=8)
        val parameters=column()
        if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(parameters,slider(getString(R.string.ui_liquid_width),FolderSettingsKeys.key(SettingsProvider.KEY_LIQUID_WIDTH),0,100,"%"))
        add(parameters,slider(getString(R.string.ui_liquid_distortion),FolderSettingsKeys.key(SettingsProvider.KEY_LIQUID_DISTORTION),0,300,"%"))
        parameters.visibility=if(number(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE))==1)View.VISIBLE else View.GONE
        refreshers.add {parameters.visibility=if(number(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE))==1)View.VISIBLE else View.GONE}
        group.addOnButtonCheckedListener {_,_,checked -> if(checked && !refreshing)reveal(parameters,number(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE))==1)}
        gate(parameters) {folderOwn() && number(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_STYLE))==1}
        add(parent,parameters,bottom=8)
    }

    private fun blurStyleSelector(parent:LinearLayout) {
        val title=row()
        title.addView(text(getString(R.string.ui_blur_style),16f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        add(parent,title,bottom=4)
        val group=com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection=true;isSelectionRequired=true
        }
        val buttons=listOf("Frosted","Liquid Glass").map { label ->
            com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                id=View.generateViewId();text=label;isAllCaps=false;textSize=14f;cornerRadius=dp(12)
                insetTop=0;insetBottom=0;minWidth=0;minimumWidth=0
                group.addView(this,LinearLayout.LayoutParams(0,dp(48),1f))
            }
        }
        var refreshing=false
        fun refresh(){refreshing=true;group.check(buttons[number(SettingsProvider.KEY_BLUR_STYLE).coerceIn(0,1)].id);refreshing=false}
        refresh()
        group.addOnButtonCheckedListener { _,id,checked ->
            if(checked && !refreshing) {
                haptic(group);save(SettingsProvider.KEY_BLUR_STYLE,buttons.indexOfFirst {it.id==id})
            }
        }
        gate(group,::blur)
        gate(buttons[1]) {blur() && android.os.Build.VERSION.SDK_INT>=33}
        refreshers.add {refresh()}
        add(parent,group,bottom=8)
        val parameters=column()
        if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(parameters,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_LIQUID_WIDTH,0,100,"%"))
        add(parameters,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_LIQUID_DISTORTION,0,300,"%"))
        parameters.visibility=if(number(SettingsProvider.KEY_BLUR_STYLE)==1)View.VISIBLE else View.GONE
        refreshers.add {parameters.visibility=if(number(SettingsProvider.KEY_BLUR_STYLE)==1)View.VISIBLE else View.GONE}
        group.addOnButtonCheckedListener {_,_,checked -> if(checked && !refreshing)reveal(parameters,number(SettingsProvider.KEY_BLUR_STYLE)==1)}
        gate(parameters) {blur() && number(SettingsProvider.KEY_BLUR_STYLE)==1}
        add(parent,parameters,bottom=8)
    }

    private fun coloringSection(parent: LinearLayout) {
        fun activeKeys(): List<String> {
            val selected=SettingsProvider.colorKeys(darkIcons())
            return if(number(SettingsProvider.KEY_ICON_APPEARANCE)==0) selected.take(2)+SettingsProvider.KEY_CLEAR_BRIGHTNESS else selected
        }
        var keys = activeKeys()
        val group = column()
        val header = row().apply { setPadding(dp(20),dp(16),dp(16),dp(16)); minimumHeight=dp(80) }
        header.addView(text(getString(R.string.ui_coloring),19f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        val mode = com.google.android.material.button.MaterialButton(this).apply {
            minimumWidth=0; minWidth=0; insetTop=0; insetBottom=0
            cornerRadius=dp(12); textSize=13f; isAllCaps=false
            setPadding(dp(14),0,dp(10),0)
            setIconResource(R.drawable.ic_expand_more); iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
            iconSize=dp(20); iconPadding=dp(6)
        }
        header.addView(mode,LinearLayout.LayoutParams(-2,dp(48)))
        add(group,header)
        val details=column().apply { setPadding(dp(20),0,dp(20),dp(16)); visibility=if(number(SettingsProvider.KEY_COLOR_MODE)==1)View.VISIBLE else View.GONE }
        add(group,details)
        var shown=number(SettingsProvider.KEY_COLOR_MODE)==1
        fun updateMode() {
            val detailed=number(SettingsProvider.KEY_COLOR_MODE)==1
            mode.text=if(detailed)getString(R.string.ui_detailed) else getString(R.string.ui_system)
            mode.contentDescription=getString(R.string.ui_color_mode_accessibility,mode.text)
            if(shown!=detailed) { shown=detailed;reveal(details,detailed) }
        }
        mode.setOnClickListener {
            haptic(it)
            androidx.appcompat.widget.PopupMenu(this,mode).apply {
                menu.add(0,0,0,getString(R.string.ui_system)).isCheckable=true
                menu.add(0,1,1,getString(R.string.ui_detailed)).isCheckable=true
                menu.setGroupCheckable(0,true,true)
                menu.findItem(number(SettingsProvider.KEY_COLOR_MODE)).isChecked=true
                setOnMenuItemClickListener { item -> save(SettingsProvider.KEY_COLOR_MODE,item.itemId);updateMode();true }
                show()
            }
        }
        val previewRow=row()
        val preview=FrameLayout(this)
        val previewShape=GradientDrawable().apply { cornerRadius=dp(18).toFloat() }
        preview.background=previewShape
        val glyph=AppCompatImageView(this).apply { setImageResource(R.drawable.ic_check);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        preview.addView(glyph,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        previewRow.addView(preview,LinearLayout.LayoutParams(dp(64),dp(64)))
        val previewLabel=text("",13f,true).apply { setPadding(dp(16),0,dp(8),0) }
        previewRow.addView(previewLabel,LinearLayout.LayoutParams(0,-2,1f))
        previewRow.addView(ConnectedActions(this,listOf(resetButton(SettingsProvider.KEY_COLOR_MODE))))
        add(details,previewRow,4,16)
        val appearances=com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection=true;isSelectionRequired=true
        }
        val appearanceButtons=listOf("CLEAR","COLOR").map { label ->
            com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                id=View.generateViewId();text=label;isAllCaps=false;textSize=14f
                cornerRadius=dp(12);insetTop=0;insetBottom=0;minWidth=0;minimumWidth=0
                appearances.addView(this,LinearLayout.LayoutParams(0,dp(48),1f))
            }
        }
        appearances.check(appearanceButtons[number(SettingsProvider.KEY_ICON_APPEARANCE).coerceIn(0,1)].id)
        add(details,appearances,bottom=12)
        var values=keys.map { number(it) }.toMutableList()
        val bands=mutableListOf<ColorBand>()
        val labels=mutableListOf<TextView>()
        fun paintPreview() {
            val palette=com.cosmod.launcherglass.util.IconPalette.from(1,values[0],values[1],values[2],darkIcons(),number(SettingsProvider.KEY_ICON_APPEARANCE)==0)
            previewShape.setColor(palette.background)
            glyph.imageTintList=ColorStateList.valueOf(palette.foreground)
            val clear=number(SettingsProvider.KEY_ICON_APPEARANCE)==0
            val accent=Color.HSVToColor(floatArrayOf(values[0].toFloat(),if(clear)0f else values[1]/100f,values[2]/100f))
            previewLabel.text=String.format("#%06X",accent and 0xffffff)
            preview.contentDescription=getString(R.string.ui_color_preview,previewLabel.text)
            bands.forEachIndexed { index,band ->
                labels[index].setTextColor(if(clear && index<2)secondaryText else primary)
                band.monochrome=clear
                band.setColor(values[0],if(clear)0 else values[1],values[2])
                labels[index].text="${values[index]}${if(index==0) "°" else "%"}"
            }
        }
        appearances.addOnButtonCheckedListener { _,id,checked ->
            if(checked && !updatingUi) {
                haptic(appearances)
                save(SettingsProvider.KEY_ICON_APPEARANCE,if(id==appearanceButtons[0].id)0 else 1)
                keys=activeKeys();values=keys.map {number(it)}.toMutableList()
                paintPreview()
            }
        }
        listOf(getString(R.string.ui_hue),getString(R.string.ui_saturation),getString(R.string.ui_brightness)).forEachIndexed { index,label ->
            val line=row()
            line.addView(text(label,14f),LinearLayout.LayoutParams(0,-2,1f))
            val value=text("",14f,bold=true).apply {setTextColor(primary)}
            labels.add(value);line.addView(value)
            val bandGroup=column()
            add(bandGroup,line,10)
            add(details,bandGroup)
            if(index<2)gate(bandGroup) { master() && number(SettingsProvider.KEY_ICON_APPEARANCE)==1 }
            val band=ColorBand(this,index).apply {
                contentDescription=label
                onChange={ next,commit -> values[index]=next;paintPreview();if(commit)save(keys[index],next) }
            }
            bands.add(band);add(bandGroup,band)
        }
        refreshers.add {
            keys=activeKeys()
            values=keys.map {number(it)}.toMutableList()
            appearances.check(appearanceButtons[number(SettingsProvider.KEY_ICON_APPEARANCE).coerceIn(0,1)].id)
            updateMode();paintPreview()
        }
        updateMode();paintPreview()
        val section=card(group)
        gates.add(0,Gate(section,::master))
        add(parent,section,bottom=12)
    }

    private fun section(parent: LinearLayout, title: String, subtitle: String, key: String,
        expanded: Boolean, onExpand: (Boolean) -> Unit, headerAllowed: () -> Boolean,
        contentAllowed: () -> Boolean, content: (LinearLayout) -> Unit) {
        val group = column()
        val header = row().apply { setPadding(dp(20),dp(16),dp(16),dp(16)); minimumHeight = dp(80) }
        val titleArea = row()
        val labels = column()
        add(labels, text(title, 19f, bold = true))
        titleArea.addView(labels, LinearLayout.LayoutParams(0,-2,1f))
        titleArea.contentDescription = "$title, ${if(expanded) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
        header.addView(titleArea, LinearLayout.LayoutParams(0,-2,1f))
        val toggle = makeSwitch(title, key)
        add(group, header)
        val children = column().apply { setPadding(dp(8),0,dp(8),dp(12)); visibility = if (expanded) View.VISIBLE else View.GONE }
        add(group, children)
        val sectionCard = card(group)
        gate(sectionCard, headerAllowed)
        gate(children, contentAllowed)
        if(subtitle.isNotBlank())add(children,text(subtitle,12f,true).apply { setPadding(dp(20),dp(4),dp(20),dp(8)) })
        content(children)
        val chevron = expandButton(expanded, title) { open ->
            onExpand(open)
            reveal(children,open)
            titleArea.contentDescription = "$title, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
        }
        header.addView(ConnectedActions(this,listOf(resetButton(key),toggle,chevron)))
        titleArea.setOnClickListener { chevron.performClick() }
        titleArea.isFocusable = true
        add(parent, sectionCard, bottom = 12)
    }

    private fun makeSwitch(label:String,key:String):SquareAction = SquareAction(this,
        if(bool(key))R.drawable.ic_check else R.drawable.ic_close,
        color(M.attr.colorOnPrimaryContainer),color(M.attr.colorPrimaryContainer),label).apply {
        isToggle=true;isChecked=bool(key)
        glyph.imageTintList=ColorStateList.valueOf(color(if(bool(key))M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))
        backgroundTintList=ColorStateList.valueOf(color(if(bool(key)) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh))
        setOnClickListener {
            haptic(it);pulse(it,500)
            val checked=!bool(key)
            animateChecked(checked)
            glyph.imageTintList=ColorStateList.valueOf(color(if(checked)M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))
            backgroundTintList=ColorStateList.valueOf(color(if(checked)M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh))
            save(key,checked)
        }
        toggleViews.add(this to key)
    }
    private fun resetKeys(key: String): List<String> = when (key) {
        SettingsProvider.KEY_COLOR_DEPTH_ENABLED -> listOf(key,SettingsProvider.KEY_COLOR_DEPTH_BITS,SettingsProvider.KEY_COLOR_DEPTH_STAGE)
        SettingsProvider.KEY_COLOR_MODE -> listOf(key,
            SettingsProvider.KEY_ICON_APPEARANCE, SettingsProvider.KEY_CLEAR_BRIGHTNESS,
            SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_COLOR_SATURATION, SettingsProvider.KEY_COLOR_BRIGHTNESS) +
            SettingsProvider.colorKeys(false) + SettingsProvider.colorKeys(true)
        SettingsProvider.KEY_BLUR_STYLE -> listOf(key,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION)
        SettingsProvider.KEY_BLUR_ENABLED -> listOf(key, SettingsProvider.KEY_BLUR_STYLE,SettingsProvider.KEY_BLUR_QUALITY,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION,SettingsProvider.KEY_BLUR_RADIUS_ENABLED, SettingsProvider.KEY_BLUR_TONE_ENABLED, SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED, SettingsProvider.KEY_BLUR_RADIUS,
            SettingsProvider.KEY_BG_OPACITY, SettingsProvider.KEY_DRAWER_BLUR_ENABLED,
            SettingsProvider.KEY_FOLDER_BLUR_ENABLED, SettingsProvider.KEY_LIVE_FPS,
            SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING)
        SettingsProvider.KEY_FOLDER_BLUR_ENABLED -> listOf(key,FolderSettingsKeys.OWN)+FolderSettingsKeys.integers.map(FolderSettingsKeys::key)+FolderSettingsKeys.booleans.map(FolderSettingsKeys::key)
        FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED) -> listOf(key)+listOf(SettingsProvider.KEY_BG_OPACITY,SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING).map(FolderSettingsKeys::key)
        FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED) -> listOf(key,FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_QUALITY))
        SettingsProvider.KEY_PHOTO_WIDGET_BLUR -> listOf(key,SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,SettingsProvider.KEY_PHOTO_WIDGET_TINT,
            SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION)
        SettingsProvider.KEY_DESIGN_ENABLED -> listOf(key,
            SettingsProvider.KEY_GLYPH_STROKE_WIDTH,
            SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,
            SettingsProvider.KEY_GLYPH_STROKE, SettingsProvider.KEY_GLYPH_STROKE_OPACITY, SettingsProvider.KEY_ICON_STROKE,
            SettingsProvider.KEY_STROKE_OPACITY, SettingsProvider.KEY_GLYPH_SHADOW,
            SettingsProvider.KEY_SHADOW_OPACITY, SettingsProvider.KEY_VOLUME_ENABLED,
            SettingsProvider.KEY_GRADIENT_OPACITY)
        SettingsProvider.KEY_GLYPH_STROKE -> listOf(key, SettingsProvider.KEY_GLYPH_STROKE_OPACITY,
            SettingsProvider.KEY_GLYPH_STROKE_WIDTH,
            SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS)
        SettingsProvider.KEY_ICON_STROKE -> listOf(key, SettingsProvider.KEY_STROKE_OPACITY)
        SettingsProvider.KEY_GLYPH_SHADOW -> listOf(key, SettingsProvider.KEY_SHADOW_OPACITY)
        SettingsProvider.KEY_BLUR_RADIUS_ENABLED -> listOf(key,SettingsProvider.KEY_BLUR_RADIUS,SettingsProvider.KEY_BLUR_QUALITY,SettingsProvider.KEY_LIVE_FPS)
        SettingsProvider.KEY_BLUR_TONE_ENABLED -> listOf(key,SettingsProvider.KEY_BG_OPACITY,
            SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING)
        SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED -> listOf(key,SettingsProvider.KEY_DRAWER_BLUR_ENABLED,SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED)
        SettingsProvider.KEY_VOLUME_ENABLED -> listOf(key, SettingsProvider.KEY_GRADIENT_OPACITY)
        else -> listOf(key)
    }
    private fun resetButton(key:String):View {
        val keys=resetKeys(key)
        val button=SquareAction(this,R.drawable.ic_restart,primary,color(M.attr.colorSurfaceContainerHigh),
            getString(R.string.ui_reset_hold))
        var holding=false
        var completed=false
        var started=0L
        var lastHoldTick=0L
        fun cancel() {
            if(!holding)return
            holding=false
            button.isPressed=false
            button.glyph.animate().rotation(0f).setDuration(198).setInterpolator(CherryMotion).start()
            (button.parent as? ConnectedActions)?.release()
            button.parent?.requestDisallowInterceptTouchEvent(false)
        }
        val tick=object:Runnable {
            override fun run() {
                if(!holding)return
                if(!button.isAttachedToWindow || !button.isEnabled) { cancel();return }
                val now=SystemClock.uptimeMillis()
                if(now-lastHoldTick>=65L){haptic(button,true);lastHoldTick=now}
                val progress=((now-started)/1500f).coerceIn(0f,1f)
                button.glyph.rotation=-360f*CherryMotion.getInterpolation(progress)
                (button.parent as? ConnectedActions)?.hold(button,progress)
                if(progress>=1f) {
                    holding=false;completed=true
                    val editor=prefs.edit()
                    keys.forEach { item -> when(val value=SettingsDefaults.value(item)) {
                        is Boolean -> editor.putBoolean(item,value)
                        is Int -> editor.putInt(item,value)
                    } }
                    editor.apply()
                    contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
                    // Synchronize the existing views. Never detach or fade the
                    // page: that caused the whole interface to blink on reset.
                    refreshControls()
                    haptic(button)
                    button.glyph.animate().rotation(-720f).setDuration(198).setInterpolator(CherryMotion)
                        .withEndAction { button.glyph.rotation=0f }.start()
                    (button.parent as? ConnectedActions)?.release()
                } else button.postDelayed(this,16)
            }
        }
        button.setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if(!view.isEnabled)return@setOnTouchListener false
                    holding=true;completed=false;started=SystemClock.uptimeMillis();lastHoldTick=started
                    button.removeCallbacks(tick)
                    button.glyph.animate().cancel();button.glyph.rotation=0f
                    view.isPressed=true;view.parent?.requestDisallowInterceptTouchEvent(true)
                    haptic(view,true);button.post(tick)
                }
                MotionEvent.ACTION_MOVE -> if(event.x<0 || event.y<0 || event.x>view.width || event.y>view.height) cancel()
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> {
                    if(!completed)cancel()
                    view.isPressed=false;view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
        button.setOnClickListener { /* A short tap deliberately cannot reset values. */ }
        cancelHolds.add { cancel();button.removeCallbacks(tick) }
        resets.add(button to keys)
        return button
    }
    private fun switchRow(label: String, key: String, subtitle: String? = null,
        expanded: Boolean = false, onExpand: ((Boolean) -> Unit)? = null): LinearLayout {
        val wrapper = column()
        val line = row().apply { setPadding(dp(16),dp(12),dp(8),dp(12)); minimumHeight = dp(72) }
        val labels = column()
        add(labels, text(label, 16f, bold = key == SettingsProvider.KEY_ENABLED))
        if (key == SettingsProvider.KEY_ENABLED && subtitle != null) add(labels,text(subtitle,12f,true),5)
        line.addView(labels, LinearLayout.LayoutParams(0,-2,1f))
        val toggle = makeSwitch(label, key)
        val actions=mutableListOf<View>(resetButton(key),toggle)
        if (onExpand != null) actions.add(expandButton(expanded,label) { open ->
            onExpand(open)
        })
        line.addView(ConnectedActions(this,actions))
        add(wrapper,line)

        labels.isClickable = true; labels.isFocusable = true
        labels.contentDescription = label
        labels.setOnClickListener { toggle.performClick() }
        return wrapper
    }

    private fun expandButton(expanded: Boolean, label: String, action: (Boolean) -> Unit): View {
        var open = expanded
        val arrow = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            imageTintList=ColorStateList.valueOf(color(M.attr.colorOnSecondaryContainer))
            rotation=if(open)-180f else 0f
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(dp(40),dp(48)).apply { leftMargin=dp(4) }
            val shape=GradientDrawable().apply {
                setColor(color(M.attr.colorSecondaryContainer))
                cornerRadius=dp(12).toFloat()
            }
            background=RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)),shape,null)
            addView(arrow,FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))
            isFocusable=true
            contentDescription="$label, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
            fun activate() {
                haptic(this)
                open=!open
                arrow.animate().cancel()
                val from=arrow.rotation
                var end=if(open)-180f else 0f
                while(end>=from)end-=360f
                val target=end
                val group=parent as? ConnectedActions
                group?.pulse(this,500)
                arrow.animate().rotation(target).setDuration(143).setInterpolator(CherryMotion).start()
                contentDescription="$label, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
                action(open)
            }
            setOnClickListener { activate() }
        }
    }

    private fun slider(label: String, key: String, min: Int, max: Int, suffix: String, hint: String? = null): LinearLayout {
        val group = column().apply { setPadding(dp(20),dp(18),dp(20),dp(10)) }
        val labels = row()
        labels.addView(text(label,14f), LinearLayout.LayoutParams(0,-2,1f))
        val value = text("${number(key)}$suffix",14f,bold = true).apply { setTextColor(primary); setPadding(dp(12),0,0,0) }
        labels.addView(value)
        add(group, labels)
        if(hint != null) add(group, text(hint,12f,true),6)
        val control = Slider(this).apply {
            valueFrom = min.toFloat(); valueTo = max.toFloat(); stepSize = 1f
            this.value = number(key).coerceIn(min,max).toFloat()
            contentDescription = label
            setLabelFormatter { "${it.roundToInt()}$suffix" }
        }
        var lastTick = 0L
        var tracking = false
        refreshers.add {
            if(!tracking) {
                control.value=number(key).coerceIn(min,max).toFloat()
                value.text="${number(key)}$suffix"
            }
        }
        control.addOnChangeListener { view, changed, user ->
            val n = changed.roundToInt(); value.text = "$n$suffix"
            if(user) {
                val now = SystemClock.uptimeMillis()
                if (now-lastTick >= 35L || n == min || n == max) { haptic(view,true); lastTick=now }
                // Keep UI movement local. Notify the launcher once when released,
                // avoiding expensive model/blur resets for every finger pixel.
                prefs.edit().putInt(key,n).apply()
                if (!tracking) { contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null); updateGates() }
            }
        }
        control.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { tracking = true; haptic(slider,true) }
            override fun onStopTrackingTouch(slider: Slider) { tracking = false; save(key,slider.value.roundToInt()) }
        })
        control.setOnKeyListener { _, _, event ->
            if(event.action == KeyEvent.ACTION_UP) { contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null) }
            false
        }
        add(group, control, 6)
        return group
    }
    private fun effectGroup(parent: LinearLayout, label: String, switchKey: String, sliderLabel: String, sliderKey: String,
        max: Int = 100, allowed: () -> Boolean = { design() }, hint: String? = null, min: Int = 0, suffix: String = "%",
        extra: ((LinearLayout) -> Unit)? = null) {
        val group = column()
        val details=column().apply { visibility=View.GONE }
        divider(details)
        val control = slider(sliderLabel,sliderKey,min,max,suffix,hint)
        gate(control) { allowed() && bool(switchKey) }
        add(details,control)
        extra?.invoke(details)
        add(group, switchRow(label,switchKey,expanded=false) { open ->
            reveal(details,open)
        })
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun glyphOutlineGroup(parent: LinearLayout) {
        val group=column()
        val details=column().apply { visibility=View.GONE }
        gate(details) { design() && bool(SettingsProvider.KEY_GLYPH_STROKE) }
        divider(details)
        add(details,slider(getString(R.string.ui_logo_outline_opacity),SettingsProvider.KEY_GLYPH_STROKE_OPACITY,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_width),SettingsProvider.KEY_GLYPH_STROKE_WIDTH,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_brightness),SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,0,100,"%"))
        add(group,switchRow(getString(R.string.ui_logo_outline),SettingsProvider.KEY_GLYPH_STROKE,expanded=false) { open -> reveal(details,open) })
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun blurToneGroup(parent:LinearLayout) {
        val group=column()
        val title=getString(R.string.ui_blur_tone)
        val details=column().apply {visibility=View.GONE}
        add(group,switchRow(title,SettingsProvider.KEY_BLUR_TONE_ENABLED,expanded=false) {open -> reveal(details,open)})
        gate(details) {blur() && bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)}
        for((label,sliderKey) in listOf(
            R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
            R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
            R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
            R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)) {
            divider(details)
            val hint=getString(when(sliderKey) {
                SettingsProvider.KEY_BG_OPACITY -> R.string.ui_plate_opacity_hint
                SettingsProvider.KEY_BLUR_VIBRANCY -> R.string.ui_blur_vibrancy_hint
                SettingsProvider.KEY_BLUR_SATURATION -> R.string.ui_blur_saturation_hint
                else -> R.string.ui_blur_lightening_hint
            })
            val control=slider(getString(label),sliderKey,0,if(sliderKey==SettingsProvider.KEY_BLUR_SATURATION || sliderKey==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",hint)
            add(details,control)
        }
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun colorDepthStages(parent:LinearLayout){
        add(parent,text(getString(R.string.ui_color_depth_stage),16f,true).apply {setPadding(dp(20),dp(12),dp(20),0)})
        val group=RadioGroup(this).apply {setPadding(dp(20),0,dp(20),dp(8))}
        val ids=mutableMapOf<Int,Int>()
        for((stage,label) in listOf(0 to R.string.ui_depth_before_blur,1 to R.string.ui_depth_after_blur,
            2 to R.string.ui_depth_after_refraction,3 to R.string.ui_depth_finished)){
            val button=com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                id=View.generateViewId();text=getString(label);textSize=14f;minHeight=dp(48);tag=stage
            }
            group.addView(button);ids[stage]=button.id
        }
        var refreshing=false
        fun refresh(){refreshing=true;ids[number(SettingsProvider.KEY_COLOR_DEPTH_STAGE)]?.let(group::check);refreshing=false}
        refresh()
        group.setOnCheckedChangeListener { _,id ->
            if(!refreshing && !updatingUi){
                val button=group.findViewById<View>(id)
                val stage=button?.tag as? Int
                if(stage!=null){haptic(button);save(SettingsProvider.KEY_COLOR_DEPTH_STAGE,stage)}
            }
        }
        refreshers.add(::refresh)
        gate(group){bool(SettingsProvider.KEY_DEVELOPER_MODE) && bool(SettingsProvider.KEY_COLOR_DEPTH_ENABLED)}
        add(parent,group)
        add(parent,text(getString(R.string.ui_material_order),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
    }

    private fun buildSettings(body: LinearLayout) {
        add(body,text(getString(R.string.ui_settings),30f,bold=true),top=8,bottom=8)
        add(body,text(getString(R.string.ui_blur_update),14f,true),bottom=24)
        val modes = column().apply { setPadding(dp(20),dp(20),dp(20),dp(12)) }
        val modeHeader = row()
        modeHeader.addView(text(getString(R.string.ui_blur_mode),18f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        modeHeader.addView(resetButton(SettingsProvider.KEY_BLUR_MODE))
        add(modes,modeHeader,bottom=12)
        val radioGroup = RadioGroup(this)
        val selected = number(SettingsProvider.KEY_BLUR_MODE)
        val modeIds=mutableMapOf<Int,Int>()
        for ((mode,label,description) in listOf(
            Triple(SettingsProvider.BLUR_MODE_LIVE,getString(R.string.ui_live_blur),getString(R.string.ui_live_blur_description)),
            Triple(SettingsProvider.BLUR_MODE_WALLPAPER,getString(R.string.ui_static_blur),getString(R.string.ui_static_blur_description)))) {
            val item = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                id=View.generateViewId(); text=label; textSize=16f; minHeight=dp(48); tag=mode
                isChecked=selected==mode
            }
            radioGroup.addView(item)
            modeIds[mode]=item.id
            // Native radio grouping owns selection and accessibility state.
            val subtitle = text(description,12f,true).apply { setPadding(dp(40),0,0,dp(12)) }
            radioGroup.addView(subtitle)
        }
        radioGroup.setOnCheckedChangeListener { group,id ->
            val button=group.findViewById<View>(id)
            val mode=button?.tag as? Int
            if(mode != null && !updatingUi) { haptic(button); save(SettingsProvider.KEY_BLUR_MODE,mode) }
        }
        refreshers.add { modeIds[number(SettingsProvider.KEY_BLUR_MODE)]?.let { radioGroup.check(it) } }
        add(modes,radioGroup)
        val modeCard=card(modes);gate(modeCard,::blur);add(body,modeCard,bottom=12)
        val idle=card(switchRow(getString(R.string.ui_energy_saving),SettingsProvider.KEY_IDLE_PAUSE,
            getString(R.string.ui_energy_saving_description)))
        gate(idle) { blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE }
        add(body,idle,bottom=24)
        val developer=column()
        add(developer,switchRow(getString(R.string.ui_developer_mode),SettingsProvider.KEY_DEVELOPER_MODE))
        val developerOptions=column()
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_color_depth),SettingsProvider.KEY_COLOR_DEPTH_ENABLED))
        add(developerOptions,text(getString(R.string.ui_color_depth_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        val colorDepth=slider(getString(R.string.ui_color_depth_bits),SettingsProvider.KEY_COLOR_DEPTH_BITS,8,24," bit")
        gate(colorDepth){bool(SettingsProvider.KEY_DEVELOPER_MODE) && bool(SettingsProvider.KEY_COLOR_DEPTH_ENABLED)}
        add(developerOptions,colorDepth)
        colorDepthStages(developerOptions)
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_haze_test),SettingsProvider.KEY_HAZE_ENABLED))
        add(developerOptions,text(getString(R.string.ui_haze_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_disable_edge_optimization),SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
        add(developerOptions,text(getString(R.string.ui_disable_edge_optimization_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_optimize_mini_icons),SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
        add(developerOptions,text(getString(R.string.ui_optimize_mini_icons_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE
        refreshers.add {developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE}
        gate(developerOptions){bool(SettingsProvider.KEY_DEVELOPER_MODE)}
        add(developer,developerOptions)
        add(body,card(developer),bottom=12)
        val logs=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_logs);minHeight=dp(48)
            setOnClickListener { button ->
                haptic(button);button.isEnabled=false
                executor.execute {
                    try {
                        val logs=GlassLogger.readLogs(this@MainActivity)
                        val directory=java.io.File(cacheDir,"diagnostics").apply {mkdirs()}
                        val timestamp=java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",java.util.Locale.US).format(java.util.Date())
                        val file=java.io.File(directory,"CherryPick_logs_$timestamp.txt")
                        val report=buildString {
                            appendLine("CherryPick Icons ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                            appendLine(java.util.Date().toString())
                            appendLine("\nSettings:")
                            prefs.all.toSortedMap().forEach { (key,value) -> appendLine("$key=$value") }
                            appendLine("\nLogs:");append(logs)
                        }
                        file.writeText(report,Charsets.UTF_8)
                        val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"${BuildConfig.APPLICATION_ID}.logs",file)
                        runOnUiThread {
                            button.isEnabled=true
                            if(!isFinishing && !isDestroyed) {
                                val send=Intent(Intent.ACTION_SEND).apply {
                                    type="text/plain"
                                    putExtra(Intent.EXTRA_STREAM,uri)
                                    putExtra(Intent.EXTRA_SUBJECT,"CherryPick Icons ${BuildConfig.VERSION_NAME} logs")
                                    clipData=ClipData.newUri(contentResolver,file.name,uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(send,getString(R.string.ui_share_logs)))
                            }
                        }
                    } catch(t:Exception) {
                        runOnUiThread {
                            button.isEnabled=true
                            if(!isFinishing)Toast.makeText(this@MainActivity,getString(R.string.ui_log_error,t.message ?: ""),Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        add(body,logs)
        val support=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_support_project);minHeight=dp(48)
            setOnClickListener {
                haptic(it)
                try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://pay.cloudtips.ru/p/e6230fb4")))}
                catch(_:Exception){Toast.makeText(context,getString(R.string.ui_link_error),Toast.LENGTH_SHORT).show()}
            }
        }
        add(body,support,top=12)
        val about=column().apply { setPadding(dp(20),dp(20),dp(20),dp(12)) }
        add(about,text("v${BuildConfig.VERSION_NAME}",12f,true))
        add(about,text("by @immorgue",14f).apply {
            setTextColor(primary);minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
            contentDescription=getString(R.string.ui_author_profile)
            setOnClickListener {
                haptic(it)
                try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://t.me/naten_e_mire"))) }
                catch(_:Exception) { Toast.makeText(context,getString(R.string.ui_telegram_error),Toast.LENGTH_SHORT).show() }
            }
        })
        add(about,text(getString(R.string.ui_credits_title),16f,bold=true),top=12)
        for((label,url) in listOf(
            "PixelLauncherEnhanced — Mahmud0808" to "https://github.com/Mahmud0808/PixelLauncherEnhanced",
            "@anime_killer2009" to "https://t.me/anime_killer2009")) {
            add(about,text(label,14f).apply {
                setTextColor(primary);minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
                setOnClickListener {
                    haptic(it)
                    try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
                    catch(_:Exception){Toast.makeText(context,getString(R.string.ui_link_error),Toast.LENGTH_SHORT).show()}
                }
            })
        }
        add(body,card(about),top=24)
    }
    private fun updateFluxWarning() {
        fluxWarning?.visibility=if(::prefs.isInitialized && prefs.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)==2)View.VISIBLE else View.GONE
    }
    private fun updateStatus() {
        updateFluxWarning()
        val lspActive=scopeState?.enabled ?: isModuleActive()
        val missing=lspActive && scopeState?.launcherSelected==false
        val unverified=lspActive && scopeState==null
        val active=lspActive && !missing && master()
        lsposedText?.text=if(lspActive)getString(R.string.ui_lsposed_enabled) else getString(R.string.ui_lsposed_disabled)
        statusText?.text=if(active)getString(R.string.ui_cherrypick_enabled) else getString(R.string.ui_cherrypick_disabled)
        val dot=when {
            missing || unverified -> Color.rgb(145,145,145)
            active -> Color.rgb(52,193,113)
            else -> Color.rgb(232,84,84)
        }
        statusDot?.backgroundTintList=ColorStateList.valueOf(dot)
        statusHint?.apply {
            visibility=if(missing || unverified)View.VISIBLE else View.GONE
            text=if(missing)getString(R.string.ui_scope_missing) else getString(R.string.ui_scope_unknown)
        }
        statusText?.contentDescription="${lsposedText?.text}. ${statusText?.text}. ${statusHint?.text.takeIf { missing || unverified } ?: ""}"
    }
    private fun restartButton(): View {
        val shape=GradientDrawable().apply {
            setColor(color(M.attr.colorPrimaryContainer))
            cornerRadius=dp(32).toFloat()
        }
        val arrow=AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_restart)
            imageTintList=ColorStateList.valueOf(color(M.attr.colorOnPrimaryContainer))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(dp(64),dp(64))
            contentDescription=getString(R.string.ui_restart_launcher)
            isFocusable=true
            background=RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)),shape,null)
            addView(arrow,FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))
            setOnClickListener { haptic(it);restart(it) }
        }
    }
    private fun restart(view: View) {
        if(restartBusy)return
        restartBusy=true;view.isEnabled=false
        Thread({
            val success=SuUtil.restartLauncher()
            runOnUiThread { restartBusy=false;view.isEnabled=true
                Toast.makeText(this,if(success)getString(R.string.ui_launcher_restarted) else getString(R.string.ui_launcher_restart_error),Toast.LENGTH_SHORT).show()
            }
        },"CherryPickRestart").start()
    }
    /** Replaced by the LSPosed activation hook. */
    @Keep fun isModuleActive(): Boolean = false
}
