package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.app.Application
import android.content.Context
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val packageName = lpparam.packageName
        GlassLogger.i("HookEntry", "handleLoadPackage called for: $packageName (PID=${android.os.Process.myPid()})")

        // 1. Module Self-Activation check in MainActivity
        if (packageName == "com.cosmod.launcherglass") {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.cosmod.launcherglass.ui.MainActivity",
                    lpparam.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
                GlassLogger.i("HookEntry", "Self-activation check hooked in MainActivity -> ACTIVE")
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to hook MainActivity self-activation", t)
            }
            return
        }

        // 2. Target ColorOS / OxygenOS Launcher
        val launcherPackages = setOf(
            "com.android.launcher",
            "com.oppo.launcher",
            "com.oplus.launcher",
            "com.coloros.launcher",
            "com.android.launcher3"
        )

        if (packageName in launcherPackages) {
            GlassLogger.i("HookEntry", ">>> Hooking ColorOS Launcher: $packageName <<<")
            try {DesktopDesignerHook.install(lpparam.classLoader)}
            catch(t:Throwable){GlassLogger.e("DesktopDesigner","Cannot install desktop editor",t)}
            try {DesktopGeometry.install(lpparam.classLoader)}
            catch(t:Throwable){GlassLogger.e("DesktopGeometry","Cannot install native desktop sizing",t)}

            var initialized = false
            fun ensureInit(context: Context, source: String) {
                if (initialized) return
                initialized = true
                val appCtx = context.applicationContext ?: context
                GlassLogger.i("HookEntry", "Initializing PrefsHelper via $source (Context: $appCtx)")
                val version=try {appCtx.packageManager.getPackageInfo(appCtx.packageName,0).versionName}catch(_:Throwable){null}
                GlassLogger.i("Compat","Launcher=${appCtx.packageName} $version, API=${android.os.Build.VERSION.SDK_INT}, module=${com.cosmod.launcherglass.BuildConfig.VERSION_NAME}")
                com.cosmod.launcherglass.util.WallpaperHelper.appContext = appCtx
                IconGlassHook.appContext = appCtx
                try {Oxygen16IconSupport.configure(appCtx)}
                catch(t:Throwable){GlassLogger.e("Compat","Split Flux setup failed; generic icon hooks kept",t)}
                PrefsHelper.init(appCtx)
                try {
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED).apply {
                        addAction(android.content.Intent.ACTION_SCREEN_OFF)
                        addAction(android.content.Intent.ACTION_SCREEN_ON)
                        addAction(android.content.Intent.ACTION_USER_PRESENT)
                    }
                    appCtx.registerReceiver(object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: android.content.Intent?) {
                            if(!PrefsHelper.enabled)return
                            when(i?.action) {
                                android.content.Intent.ACTION_SCREEN_OFF -> {
                                    com.cosmod.launcherglass.util.LauncherWallpaperCapture.onScreenOff()
                                    com.cosmod.launcherglass.util.LiveBlurEngine.onActivityPaused()
                                    return
                                }
                                android.content.Intent.ACTION_SCREEN_ON -> {
                                    com.cosmod.launcherglass.util.LauncherWallpaperCapture.prepareUnlock();return
                                }
                                android.content.Intent.ACTION_USER_PRESENT -> {
                                    com.cosmod.launcherglass.util.LauncherWallpaperCapture.onUserPresent();return
                                }
                            }
                            GlassLogger.i("HookEntry", "Wallpaper changed, invalidating blur cache")
                            com.cosmod.launcherglass.util.WallpaperHelper.onSystemWallpaperChanged()
                            com.cosmod.launcherglass.util.LiveBlurEngine.invalidate()
                        }
                    }, filter)
                    val wallpaperHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val refreshWallpaper = Runnable {
                        if(!PrefsHelper.enabled)return@Runnable
                        com.cosmod.launcherglass.util.WallpaperHelper.onSystemWallpaperChanged()
                    }
                    val wallpaperObserver = object : android.database.ContentObserver(wallpaperHandler) {
                        override fun onChange(selfChange: Boolean) {
                            if(!PrefsHelper.enabled)return
                            wallpaperHandler.removeCallbacks(refreshWallpaper)
                            wallpaperHandler.postDelayed(refreshWallpaper, 200L)
                        }
                    }
                    for (key in listOf("is_desktop_wallpaper_blur_enable",
                        "com.oplus.wallpapers.isLauncherEffectWallpaper",
                        "com.oplus.wallpapers.isLauncherEffectWallpaper_sub")) {
                        appCtx.contentResolver.registerContentObserver(
                            android.provider.Settings.Secure.getUriFor(key), false, wallpaperObserver)
                    }
                    appCtx.contentResolver.registerContentObserver(
                        android.provider.Settings.System.getUriFor("WallpaperScreenshotID"), false, wallpaperObserver)
                } catch (_: Throwable) {}
            }

            // Hook 1: com.android.common.LauncherApplication (ColorOS Application class)
            try {
                val launcherAppClass = XposedHelpers.findClassIfExists("com.android.common.LauncherApplication", lpparam.classLoader)
                if (launcherAppClass != null) {
                    XposedHelpers.findAndHookMethod(
                        launcherAppClass,
                        "onCreate",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val app = param.thisObject as? Application ?: return
                                GlassLogger.i("HookEntry", "LauncherApplication.onCreate() intercepted!")
                                ensureInit(app, "LauncherApplication.onCreate")
                            }
                        }
                    )
                    GlassLogger.i("HookEntry", "Hooked LauncherApplication.onCreate")
                }
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to hook LauncherApplication.onCreate", t)
            }

            // Hook 2: com.android.launcher.Launcher lifecycle & touch events
            try {
                val launcherActivityClass = XposedHelpers.findClassIfExists("com.android.launcher.Launcher", lpparam.classLoader)
                if (launcherActivityClass != null) {
                    XposedHelpers.findAndHookMethod(
                        launcherActivityClass,
                        "onCreate",
                        android.os.Bundle::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val act = param.thisObject as? Activity ?: return
                                GlassLogger.i("HookEntry", "Launcher.onCreate() activity intercepted!")
                                ensureInit(act, "Launcher.onCreate")
                            }
                        }
                    )

                    ModuleHooks.findAndHookMethod(
                        launcherActivityClass,
                        "onResume",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                (param.thisObject as? Activity)?.window?.decorView?.let {
                                    com.cosmod.launcherglass.util.LauncherWallpaperCapture.setWindow(it)
                                }
                                com.cosmod.launcherglass.util.LiveBlurEngine.onActivityResumed()
                                com.cosmod.launcherglass.util.WallpaperHelper.onLauncherWindowReady()
                            }
                        }
                    )

                    ModuleHooks.findAndHookMethod(
                        launcherActivityClass,
                        "onPause",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                com.cosmod.launcherglass.util.LiveBlurEngine.onActivityPaused()
                            }
                        }
                    )

                    ModuleHooks.findAndHookMethod(
                        launcherActivityClass,
                        "dispatchTouchEvent",
                        android.view.MotionEvent::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val event = param.args[0] as? android.view.MotionEvent ?: return
                                com.cosmod.launcherglass.util.LiveBlurEngine.onTouch(event)
                            }
                        }
                    )

                    GlassLogger.i("HookEntry", "Hooked Launcher lifecycle and touch events")
                }
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to hook Launcher", t)
            }

            // Hook 3: Workspace scroll tracking for live blur and parallax (ONLY Workspace, NEVER RecentsView!)
            val pagedClasses = listOf(
                "com.android.launcher3.Workspace",
                "com.oplus.launcher.Workspace"
            )
            for (pagedName in pagedClasses) {
                try {
                    val pagedClass = XposedHelpers.findClassIfExists(pagedName, lpparam.classLoader) ?: continue
                    ModuleHooks.findAndHookMethod(
                        pagedClass,
                        "onScrollChanged",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val view = param.thisObject as? android.view.View ?: return
                                val name = view.javaClass.name
                                if (!name.contains("Workspace") || name.contains("Recents") || name.contains("Task")) {
                                    return
                                }
                                val l = param.args[0] as Int
                                com.cosmod.launcherglass.util.LiveBlurEngine.onScroll(l, view)
                            }
                        }
                    )
                    GlassLogger.i("HookEntry", "Hooked $pagedName.onScrollChanged for live blur tracking")
                } catch (_: Throwable) {}
            }

            // Hook 4: Fallback generic Application.onCreate
            try {
                XposedHelpers.findAndHookMethod(
                    Application::class.java,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.thisObject as? Application ?: return
                            ensureInit(app, "Application.onCreate")
                        }
                    }
                )
            } catch (_: Throwable) {}

            // Install Icon hooks
            for (name in listOf(
                "com.android.launcher3.Launcher",
                "com.android.launcher3.views.AppLauncher"
            )) {
                try {
                    val cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
                    ModuleHooks.hookAllMethods(cls, "startActivitySafely", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            com.cosmod.launcherglass.util.LiveBlurEngine.onAppLaunchStarted()
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == false || param.hasThrowable()) {
                                com.cosmod.launcherglass.util.LiveBlurEngine.onHomeReturnStarted()
                            }
                        }
                    })
                } catch (t: Throwable) {
                    GlassLogger.e("HookEntry", "Failed to suspend blur on app launch: $name", t)
                }
            }

            try {
                ModuleHooks.findAndHookMethod(
                    "com.android.launcher3.dragndrop.DragInterfaceManager",
                    lpparam.classLoader,
                    "notifyEditStateChanged",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args[0] == true) {
                                com.cosmod.launcherglass.util.LiveBlurEngine.onEditModeChanged(true)
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.args[0] == false) {
                                com.cosmod.launcherglass.util.LiveBlurEngine.onEditModeChanged(false)
                            }
                        }
                    }
                )
                GlassLogger.i("HookEntry", "Hooked workspace edit mode blur suspension")
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to hook workspace edit mode", t)
            }

            // Target state gives an early signal before the opening animation;
            // progress catches interactive swipes and reversals without a timer.
            for (name in listOf(
                "com.android.launcher3.allapps.AllAppsTransitionController",
                "com.android.launcher3.allapps.OplusAllAppsTransitionController"
            )) {
                try {
                    val cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
                    val stateClass = XposedHelpers.findClass("com.android.launcher3.LauncherState", lpparam.classLoader)
                    val stateHook = object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = param.args.firstOrNull() ?: return
                            if (!stateClass.isInstance(state)) return
                            val allApps = XposedHelpers.getStaticObjectField(stateClass, "ALL_APPS")
                            com.cosmod.launcherglass.util.LiveBlurEngine.onDrawerTargetChanged(state === allApps)
                        }
                    }
                    ModuleHooks.hookAllMethods(cls, "setState", stateHook)
                    ModuleHooks.hookAllMethods(cls, "setStateWithAnimation", stateHook)
                    ModuleHooks.hookAllMethods(cls, "setProgress", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val progress = param.args.firstOrNull() as? Float ?: return
                            com.cosmod.launcherglass.util.LiveBlurEngine.onDrawerProgress(progress)
                        }
                    })
                    GlassLogger.i("HookEntry", "Hooked $name for drawer/workspace blur handoff")
                } catch (t: Throwable) {
                    GlassLogger.e("HookEntry", "Failed to hook drawer blur handoff: $name", t)
                }
            }

            try {
                val wallpaperClass = XposedHelpers.findClassIfExists(
                    "com.android.launcher.wallpaper.LauncherWallpaperManager", lpparam.classLoader)
                if(wallpaperClass==null)GlassLogger.i("Compat","Processed wallpaper delivery unavailable; native wallpaper capture fallback kept")
                if (wallpaperClass != null) {
                    // In 17.3.9 this method is "u", in 17.3.12 it is "s" -> resolve dynamically by Bitmap parameter
                    val deliveryMethod = wallpaperClass.declaredMethods.firstOrNull { method ->
                        method.parameterTypes.isNotEmpty() && method.parameterTypes[0] == android.graphics.Bitmap::class.java
                    }
                    if (deliveryMethod != null) {
                        ModuleHooks.hookMethod(deliveryMethod, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val bitmap = param.args.firstOrNull() as? android.graphics.Bitmap ?: return
                                com.cosmod.launcherglass.util.WallpaperHelper.onLauncherWallpaper(bitmap)
                            }
                        })
                        GlassLogger.i("HookEntry", "Hooked processed ColorOS wallpaper delivery (${deliveryMethod.name})")
                    } else {
                        for (name in listOf("u", "s")) {
                            try {
                                ModuleHooks.hookAllMethods(wallpaperClass, name, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val bitmap = param.args.firstOrNull() as? android.graphics.Bitmap ?: return
                                        com.cosmod.launcherglass.util.WallpaperHelper.onLauncherWallpaper(bitmap)
                                    }
                                })
                                GlassLogger.i("HookEntry", "Hooked processed ColorOS wallpaper delivery: $name")
                                break
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to hook processed wallpaper delivery", t)
            }

            try {IconGlassHook.init(lpparam)}
            catch(t:Throwable){GlassLogger.e("Compat","Icon hook setup incomplete; see failing API above",t)}
            try {
                val config = XposedHelpers.findClassIfExists(
                    "com.android.launcher.theme.LauncherIconConfig", lpparam.classLoader)
                if (config != null) {
                    var lastConfig: String? = null
                    var needsReload = false
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val reset = Runnable {
                        if(!PrefsHelper.enabled)return@Runnable
                        GlassLogger.i("HookEntry", "Icon pack/style changed -> reset glass caches and frame scheduler")
                        IconGlassHook.appContext?.let { PrefsHelper.loadPrefs(it) }
                        IconGlassHook.onIconThemeChanged()
                        // A cache reset alone leaves native views bound to the
                        // previous pack. Rebind once after configuration settles.
                        if (needsReload) {
                            needsReload = false
                            IconGlassHook.reloadNativeIcons()
                        }
                        com.cosmod.launcherglass.util.LiveBlurEngine.onPrefsChanged()
                    }
                    val changed = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fingerprint = try {
                                val instance = param.thisObject
                                    ?: XposedHelpers.getStaticObjectField(config, "sInstance") ?: return
                                val nativeConfig = XposedHelpers.getObjectField(instance, "mIconConfig") ?: return
                                val values = nativeConfig?.javaClass?.declaredFields
                                    ?.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                                        (it.type.isPrimitive || it.type == String::class.java) }
                                    ?.sortedBy { it.name }?.joinToString { field ->
                                        field.isAccessible = true
                                        "${field.name}=${field.get(nativeConfig)}"
                                    }
                                val shapeValues=listOf("mSettingCorner","mG2SettingCornerRatio","mRadiusRatio","mRadiusType").joinToString { name ->
                                    "$name=${try {XposedHelpers.getObjectField(instance,name)}catch(_:Throwable){null}}"
                                }
                                "${XposedHelpers.getObjectField(instance, "mIconPackName")}:$values:$shapeValues"
                            } catch (_: Throwable) { return }
                            synchronized(handler) {
                                if (lastConfig == fingerprint) return
                                if (lastConfig != null) needsReload = true
                                lastConfig = fingerprint
                                handler.removeCallbacks(reset)
                                handler.postDelayed(reset, 250L)
                            }
                        }
                    }
                    for (method in listOf("update", "updateIconConfigIfNecessary", "updateSettingConfig")) {
                        ModuleHooks.hookAllMethods(config, method, changed)
                    }
                }
            } catch (t: Throwable) {
                GlassLogger.e("HookEntry", "Failed to track icon-pack changes", t)
            }
        }
    }
}
