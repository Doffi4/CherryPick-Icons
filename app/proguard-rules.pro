# Keep Xposed entrypoints
-keep class com.cosmod.launcherglass.xposed.HookEntry { *; }
-keepclassmembers class com.cosmod.launcherglass.xposed.HookEntry { *; }

# Keep UI and Provider
-keep class com.cosmod.launcherglass.ui.** { *; }
-keep class com.cosmod.launcherglass.xposed.** { *; }

# Keep Xposed API classes
-dontwarn de.robv.android.xposed.**
