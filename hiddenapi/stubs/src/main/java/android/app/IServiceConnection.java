package android.app;

import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IServiceConnection extends IInterface {

    /**
     * Old signature, used on Android 8.1 ~ 16. Removed from the framework
     * interface in Android 17, but kept here so that code compiling against
     * this stub can still override it (the method is simply never invoked on
     * Android 17+).
     */
    void connected(ComponentName name, IBinder service, boolean dead);

    /**
     * New signature introduced in Android 17 (API 36+). On older Android
     * versions this overload does not exist at runtime and is never invoked,
     * so overriding it is harmless there.
     */
    void connected(ComponentName name, IBinder service, IBinderSession session, boolean dead);

    abstract class Stub extends Binder implements IServiceConnection {

        public static IServiceConnection asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException();
        }
    }
}
