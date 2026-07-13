package android.app;

import android.os.IBinder;
import android.os.IInterface;

/**
 * Stub of {@code android.app.IBinderSession} introduced in Android 17 (API 36+).
 *
 * <p>This interface is used as a parameter of the new
 * {@link IServiceConnection#connected} overload added in Android 17. On older
 * Android versions this class does not exist at runtime, but because the stub
 * is only referenced from the new {@code connected} overload (which is never
 * invoked on older versions), the absence of the runtime class is not a
 * problem — the method is never dispatched there.
 */
public interface IBinderSession extends IInterface {

    String DESCRIPTOR = "android.app.IBinderSession";

    void binderTransactionCompleted(long transactionId);

    long binderTransactionStarting(String name);

    abstract class Stub extends android.os.Binder implements IBinderSession {

        public static IBinderSession asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IBinder asBinder() {
            throw new UnsupportedOperationException();
        }
    }
}
