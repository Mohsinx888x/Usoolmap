-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Clip index and play history are stored as JSON by field name.
-keepattributes Signature
-keep class com.usool.map.voice.humour.LocalClip { *; }
-keep class com.usool.map.voice.humour.PendingPlay { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Called from the globe page's JavaScript.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
