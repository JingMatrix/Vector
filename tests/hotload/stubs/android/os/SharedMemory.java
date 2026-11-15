package android.os;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;

/** Models input ownership and cleanup failures, not Android ashmem or ART mappings. */
public class SharedMemory implements AutoCloseable {
    public static final IdentityHashMap<ByteBuffer, SharedMemory> mapped = new IdentityHashMap<>();
    public final int id;
    public int maps, unmaps, closes;
    public RuntimeException mapFailure, unmapFailure, closeFailure;
    public SharedMemory(int id) { this.id = id; }
    public ByteBuffer mapReadOnly() {
        if (mapFailure != null) throw mapFailure;
        maps++;
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(0, id);
        buffer = buffer.asReadOnlyBuffer();
        mapped.put(buffer, this);
        return buffer;
    }
    public static void unmap(ByteBuffer buffer) {
        SharedMemory owner = mapped.remove(buffer);
        if (owner == null) throw new AssertionError("unknown mapping");
        owner.unmaps++;
        if (owner.unmapFailure != null) throw owner.unmapFailure;
    }
    public void close() {
        closes++;
        if (closeFailure != null) throw closeFailure;
    }
}
