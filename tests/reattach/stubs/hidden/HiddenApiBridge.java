package hidden;
import android.os.IBinder;
import android.content.Context;
public class HiddenApiBridge {
    public static IBinder Binder_allowBlocking(IBinder binder) { return binder; }
    public static IBinder Context_getActivityToken(Context context) { return null; }
}
