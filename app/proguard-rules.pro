# libxposed api 的注解是 compileOnly 传递依赖，运行时不存在
-dontwarn io.github.libxposed.annotation.**

# 入口类名写在 META-INF/xposed/java_init.list 里，R8 混淆类名后需要同步改写该文件
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 框架用无参构造器反射实例化入口类
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
