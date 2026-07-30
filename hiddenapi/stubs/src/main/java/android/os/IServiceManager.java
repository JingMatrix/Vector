package android.os;

public interface IServiceManager extends IInterface {

    IBinder getService(String name);

    public void registerForNotifications(String name, IServiceCallback cb);

    abstract class Stub extends Binder implements IServiceManager {
        public static IServiceManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
