# The paywall page calls into this class from JavaScript.
-keepclassmembers class co.fictura.sdk.PaywallActivity$Bridge { @android.webkit.JavascriptInterface <methods>; }
