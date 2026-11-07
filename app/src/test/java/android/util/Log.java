package android.util;

/** Host tests exercise the Kotlin DSP fallback without an Android logging service. */
public final class Log {
    public static int d(String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
}
