-keep,allowobfuscation class com.example.hooktest.MainHook
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onSystemServerStarting(...);
}
