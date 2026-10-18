package android.os;
import java.io.Serializable;
import java.util.HashMap;
public class Bundle {
    private final HashMap<String, Object> values = new HashMap<>();
    public Bundle() {}
    public Bundle(Bundle other) { values.putAll(other.values); }
    public void putSerializable(String key, Serializable value) { values.put(key, value); }
    public Serializable getSerializable(String key) { return (Serializable) values.get(key); }
    public <T extends Serializable> T getSerializable(String key, Class<T> type) {
        return type.cast(values.get(key));
    }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public boolean getBoolean(String key, boolean fallback) {
        return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
    }
}
