package android.os;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

/** Deterministic transport double: death delivery can lag behind a new connection. */
public class Binder implements IBinder {
    public static int callerUid = 1000, callerPid = 123;
    public boolean alive = true, rejectLink = false;
    public final List<DeathRecipient> deaths = new ArrayList<>();
    private IInterface owner;
    private String descriptor;
    public Binder() {}
    public void attachInterface(IInterface owner, String descriptor) {
        this.owner = owner;
        this.descriptor = descriptor;
    }
    public static int getCallingUid() { return callerUid; }
    public static int getCallingPid() { return callerPid; }
    public static long clearCallingIdentity() {
        long saved = ((long) callerUid << 32) | (callerPid & 0xffffffffL);
        callerUid = Process.myUid(); callerPid = Process.myPid();
        return saved;
    }
    public static void restoreCallingIdentity(long saved) {
        callerUid = (int) (saved >>> 32); callerPid = (int) saved;
    }
    public String getInterfaceDescriptor() { return descriptor; }
    public IInterface queryLocalInterface(String name) { return name.equals(descriptor) ? owner : null; }
    public boolean isBinderAlive() { return alive; }
    public boolean pingBinder() { return alive; }
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        if (rejectLink || !alive) throw new RemoteException("dead");
        deaths.add(recipient);
    }
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return deaths.remove(recipient); }
    public void die() {
        alive = false;
        for (DeathRecipient recipient : List.copyOf(deaths)) recipient.binderDied();
    }
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        data.setDataPosition(0);
        return onTransact(code, data, reply, flags);
    }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return false;
    }
    public void dump(FileDescriptor fd, String[] args) {}
    public void dumpAsync(FileDescriptor fd, String[] args) {}
}
