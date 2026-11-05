# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.pocketagent.android.**$$serializer { *; }
-keepclassmembers class app.pocketagent.android.** {
    *** Companion;
}

# --- PocketAgent ---
# kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep class app.pocketagent.android.data.** { *; }
# SSH 客户端与 BouncyCastle（算法名靠反射找实现）
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Compose 运行时不需要额外规则，避免误裁

# sshj 的可选 GSSAPI / eddsa 依赖在 Android 上不存在，忽略缺失类
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.x509.X509Key
-dontwarn net.i2p.crypto.eddsa.**
