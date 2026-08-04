package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IApplicationThread extends IInterface {
    abstract class Stub extends Binder implements IApplicationThread {
        public static IApplicationThread asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
