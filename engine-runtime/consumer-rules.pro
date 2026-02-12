# Consumer rules for engine-runtime module

# Keep plugin interface and implementations
-keep class * implements com.tomandy.palmclaw.engine.Plugin { *; }

# Keep plugin metadata classes
-keep class com.tomandy.palmclaw.engine.PluginMetadata { *; }
-keep class com.tomandy.palmclaw.engine.ToolDefinition { *; }
