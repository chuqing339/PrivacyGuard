# Proguard rules for PrivacyGuard

# [保留原因] Room / DataStore / libsu 等框架依赖运行时注解反射，注解被裁剪会破坏其工作路径
-keepattributes *Annotation*

# [保留原因] Room 生成的 DAO/Entity 实现经由注解与 KSP 生成代码交互，混淆成员会破坏
# Room 的生成实现与数据绑定；保守保留（Release 运行时未在本环境验证）。
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keep class androidx.room.** { *; }

# [保留原因] data 层为 Room 实体与 DataStore 读写的数据模型，持久化反序列化依赖成员名，
# 混淆会导致已落库/已写入数据读取失败；保守保留（Release 运行时未在本环境验证）。
-keep class com.privacyguard.app.data.** { *; }

# libsu
# [保留原因] libsu 通过注解与 Binder/AIDL 代理访问 RootService 相关类，混淆会破坏其
# 反射加载与跨进程绑定；保守保留（Release 运行时未在本环境验证）。
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Miuix
# [保留原因] Miuix 组件在主题/资源解析路径存在反射查找，混淆可能引发运行时异常；
# 保守保留（Release 运行时未在本环境验证）。
-keep class top.yukonga.miuix.** { *; }
-dontwarn top.yukonga.miuix.**

# AndroidX & Compose
# [保留原因] Compose 编译器插件生成代码与协程元数据在 R8 优化期可能产生缺失引用告警，
# 仅压制告警不做 keep；保守保留（Release 运行时未在本环境验证）。
-dontwarn androidx.compose.**
-dontwarn kotlinx.coroutines.**

# [保留原因] DataStore 通过生成的 serializer / 反射访问偏好数据类，混淆会破坏反序列化；
# 保守保留（Release 运行时未在本环境验证）。
-keep class androidx.datastore.** { *; }
