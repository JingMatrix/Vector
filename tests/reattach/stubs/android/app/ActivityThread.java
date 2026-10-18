package android.app;
import android.os.Binder;
import android.os.IBinder;
public class ActivityThread {
    private static final ActivityThread INSTANCE = new ActivityThread();
    private final ApplicationThread thread = new ApplicationThread();
    public static ActivityThread currentActivityThread() { return INSTANCE; }
    public ApplicationThread getApplicationThread() { return thread; }
    public ContextImpl getSystemContext() { return new ContextImpl(); }
    public class ApplicationThread extends Binder implements IApplicationThread {
        public IBinder asBinder() { return this; }
    }
}
