# Consumer proguard rules for plugin-manager

# Keep public API classes
-keep public class com.tomandy.palmclaw.pluginmanager.UserPluginManager { *; }
-keep public class com.tomandy.palmclaw.pluginmanager.PluginPreferences { *; }
-keep public class com.tomandy.palmclaw.pluginmanager.InstallPluginTool { *; }
