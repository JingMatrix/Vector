package android.util;
public class Log {
    public static Throwable lastFailure;
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable t) { return 0; }
    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable t) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable t) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable t) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable t) { lastFailure = t; return 0; }
    public static int println(int priority, String tag, String message) { return 0; }
}
