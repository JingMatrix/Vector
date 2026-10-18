package org.matrix.vector.daemon.data;
import java.util.*;
public class ModuleDatabase {
    public static final ModuleDatabase INSTANCE = new ModuleDatabase();
    public final Set<String> scopes = new HashSet<>();
    public static class EnabledModuleRow {
        private final String name;
        public EnabledModuleRow(String name, String ignoredPath) { this.name = name; }
        public String getPackageName() { return name; }
    }
    public List<EnabledModuleRow> systemServerModuleRows() {
        return scopes.stream().map(name -> new EnabledModuleRow(name, null)).toList();
    }
}
