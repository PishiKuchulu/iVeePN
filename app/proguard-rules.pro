# Strip debug/verbose log calls (they carry connection targets) from
# release builds even where they bypass AppLogger.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

-keep class com.pishi.iveepn.vpn.tun.HevSocks5Tunnel { *; }
-keepnames class com.pishi.iveepn.vpn.tun.HevSocks5Tunnel
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn com.google.errorprone.annotations.**
