# OkHttp, Okio, coroutines and AndroidX ship their own consumer rules; nothing extra is needed.
# Components referenced from the manifest (service, provider, activity) are kept automatically.

# Strip verbose/debug logging from release builds as a second line of defence
# (the app already only logs diagnostics when BuildConfig.DEBUG is true).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
-assumevalues class android.util.Log {
    public static boolean isLoggable(java.lang.String, int) return false;
}
