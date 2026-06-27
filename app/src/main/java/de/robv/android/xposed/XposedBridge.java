
package de.robv.android.xposed;

public class XposedBridge {
    public static void log(String text) {
        android.util.Log.i("Xposed", text);
    }
}
