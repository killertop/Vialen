-repackageclasses ''
-allowaccessmodification

# Native entry points are looked up by class and method name, not Java call sites.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Imported/exported profile field names form a persistent JSON wire schema.
# Constructors are also called reflectively by the subscription parser.
-keep class * extends io.nekohasekai.sagernet.fmt.AbstractBean {
    <fields>;
    public <init>();
}
-keep class io.nekohasekai.sagernet.fmt.AbstractBean {
    <fields>;
    public <init>();
}
-keep class moe.matsuri.nb4a.SingBoxOptions$* {
    <fields>;
    public <init>();
}
-keep class io.nekohasekai.sagernet.fmt.v2ray.VmessQRCode {
    <fields>;
    public <init>();
}

# Workers persisted by previous releases must retain their binary class names.
# Constructor and framework reflection rules are supplied by WorkManager/Room.
-keepnames class * extends androidx.work.ListenableWorker

# Clean Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNull(java.lang.Object);
    static void checkNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}

-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException
