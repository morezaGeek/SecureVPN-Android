# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified in 
# /sdk/tools/proguard/proguard-android.txt

# Keep VPN related classes
-keep class com.vpnapp.vpn.** { *; }
-keep class go.** { *; }
-keep class io.nekohasekai.** { *; }
-keep class org.conscrypt.** { *; }

# CRITICAL: Keep libopenconnect JNI classes and callback methods
# These are called from native code and R8 cannot detect usage
-keep class org.infradead.libopenconnect.** { *; }
-keepclassmembers class org.infradead.libopenconnect.LibOpenConnect {
    native <methods>;
    *;
}
-keepclassmembers class * extends org.infradead.libopenconnect.LibOpenConnect {
    *;
}

# Keep BouncyCastle for DTLS
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn go.**
-dontwarn org.conscrypt.**
-dontwarn org.apache.harmony.**
-dontwarn io.nekohasekai.**
-dontwarn org.infradead.libopenconnect.**
