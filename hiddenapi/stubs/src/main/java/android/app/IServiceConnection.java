package android.app;

import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IServiceConnection extends IInterface {

    /** Declared by the framework up to Android 16. */
    void connected(ComponentName name, IBinder service, boolean dead);

    /** Declared by the framework from Android 17 (API 37) on. */
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
