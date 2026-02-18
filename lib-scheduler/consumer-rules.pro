# Consumer proguard rules for lib-scheduler

# Keep public API classes
-keep public class com.tomandy.oneclaw.scheduler.CronjobManager { *; }
-keep public class com.tomandy.oneclaw.scheduler.data.CronjobEntity { *; }
-keep public class com.tomandy.oneclaw.scheduler.data.ExecutionLog { *; }
