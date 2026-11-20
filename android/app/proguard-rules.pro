-dontwarn **
-keep class dev.webhub.** { *; }
# JS 接口方法必须保留名字
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
