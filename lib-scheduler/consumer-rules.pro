# Consumer proguard rules for lib-scheduler

# Keep public API classes
-keep public class com.tomandy.palmclaw.scheduler.CronjobManager { *; }
-keep public class com.tomandy.palmclaw.scheduler.data.CronjobEntity { *; }
-keep public class com.tomandy.palmclaw.scheduler.data.ExecutionLog { *; }
