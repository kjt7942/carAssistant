# 화면(JS)에서 이름으로 부르는 기능은 이름을 그대로 둔다.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.kjt7942.carassistant.MainActivity { *; }
-keep class com.kjt7942.carassistant.MainActivity$Bridge { *; }
-keep class com.kjt7942.carassistant.UpdatedReceiver { *; }
-dontobfuscate
