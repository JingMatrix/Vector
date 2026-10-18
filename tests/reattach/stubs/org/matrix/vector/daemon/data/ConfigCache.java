package org.matrix.vector.daemon.data;
import java.util.*;
import org.matrix.vector.ipc.LoadedModule;
/** Configuration I/O is controlled; the registry and client under test are production classes. */
public class ConfigCache {
    public static final ConfigCache INSTANCE = new ConfigCache();
    public final Map<String, LoadedModule> modules = new HashMap<>();
    public boolean ready = true;
    public DaemonState getState() {
        return new DaemonState(false, ready, -1, null, modules, Map.of(), Map.of());
    }
    public void ensureCacheReady$daemon() {}
    public List<LoadedModule> getModulesForSystemServer() { return new ArrayList<>(modules.values()); }
}
