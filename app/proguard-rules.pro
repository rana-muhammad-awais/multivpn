# Keep the embedded OpenVPN engine intact (JNI + reflection use)
-keep class de.blinkt.openvpn.** { *; }
-keep class org.spongycastle.** { *; }
-dontwarn de.blinkt.openvpn.**
