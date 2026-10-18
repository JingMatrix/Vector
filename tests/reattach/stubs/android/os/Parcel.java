package android.os;
import java.util.ArrayList;
public class Parcel {
    private final ArrayList<Object> values = new ArrayList<>();
    private int position;
    public static Parcel obtain() { return new Parcel(); }
    public void writeInt(int value) { values.add(value); }
    public int readInt() { return (Integer) values.get(position++); }
    public void writeString(String value) { values.add(value); }
    public String readString() { return (String) values.get(position++); }
    public void writeStrongBinder(IBinder value) { values.add(value); }
    public IBinder readStrongBinder() { return (IBinder) values.get(position++); }
    public void writeNoException() {}
    public void writeException(Exception e) { throw new AssertionError(e); }
    public void setDataPosition(int position) { this.position = position; }
    public void recycle() {}
}
