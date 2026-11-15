import android.os.SharedMemory;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;
import org.matrix.vector.impl.core.OnDemandSerialExecutor;
import org.matrix.vector.impl.utils.DexMemoryScopeKt;

/** Executes the compiled production queue and DEX ownership helpers. */
public class ResourceCleanupTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(15, TimeUnit.SECONDS), "worker did not finish");
    }
    private static void retired(OnDemandSerialExecutor executor, List<Thread> workers) throws Exception {
        for (Thread worker : workers) {
            worker.join(15000);
            check(!worker.isAlive(), "idle worker remains alive");
        }
        Field queue = OnDemandSerialExecutor.class.getDeclaredField("queue");
        queue.setAccessible(true);
        check(queue.get(executor) == null, "finished queue retains storage");
    }
    private static void queue() throws Exception {
        List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            workers.add(thread);
            return thread;
        };
        AtomicInteger failures = new AtomicInteger();
        OnDemandSerialExecutor executor = new OnDemandSerialExecutor(factory, failure -> {
            failures.incrementAndGet();
            throw new IllegalStateException("reporting failed");
        });
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        });
        await(entered);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2000);
        for (int i = 0; i < 2000; i++) {
            final int id = i;
            executor.execute(() -> { order.add(id); done.countDown(); });
        }
        release.countDown(); await(done); retired(executor, workers);
        check(order.equals(java.util.stream.IntStream.range(0, 2000).boxed().toList()), "queue is not FIFO");
        check(workers.size() == 1, "one batch starts multiple workers");

        int count = 8000;
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch parallelDone = new CountDownLatch(count);
        ExecutorService producers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < count; i++) {
            final int id = i;
            producers.execute(() -> executor.execute(() -> {
                int running = active.incrementAndGet();
                max.accumulateAndGet(running, Math::max);
                seen.add(id); active.decrementAndGet(); parallelDone.countDown();
            }));
        }
        producers.shutdown();
        check(producers.awaitTermination(15, TimeUnit.SECONDS), "producers stalled");
        await(parallelDone); retired(executor, workers);
        check(seen.size() == count && max.get() == 1, "concurrent submissions lost or overlap callbacks");

        CountDownLatch recovered = new CountDownLatch(1);
        executor.execute(() -> { Thread.currentThread().interrupt(); throw new IllegalStateException("task failed"); });
        AtomicBoolean inheritedInterrupt = new AtomicBoolean(true);
        executor.execute(() -> { inheritedInterrupt.set(Thread.currentThread().isInterrupted()); recovered.countDown(); });
        await(recovered); retired(executor, workers);
        check(failures.get() == 1 && !inheritedInterrupt.get(), "task/reporting failure strands queue or interrupt leaks");
        for (int i = 0; i < 100; i++) {
            CountDownLatch cycle = new CountDownLatch(1);
            executor.execute(cycle::countDown);
            await(cycle); retired(executor, workers);
        }

        AtomicBoolean failStart = new AtomicBoolean(true);
        OnDemandSerialExecutor retry = new OnDemandSerialExecutor(runnable -> {
            if (failStart.getAndSet(false)) return new Thread(runnable) {
                @Override public synchronized void start() { throw new IllegalStateException("start failed"); }
            };
            return factory.newThread(runnable);
        }, failure -> Unit.INSTANCE);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try { retry.execute(() -> rejectedRan.set(true)); throw new AssertionError("expected start failure"); }
        catch (IllegalStateException expected) {}
        CountDownLatch retryDone = new CountDownLatch(1);
        retry.execute(retryDone::countDown); await(retryDone); retired(retry, workers);
        check(!rejectedRan.get(), "failed submission later executes");
        System.out.println("PASS queue: FIFO, 8000 submissions, 100 drained batches, failure/reporter/interrupt/start recovery");
    }

    private static void dex() {
        SharedMemory first = new SharedMemory(1), second = new SharedMemory(2);
        Object token = new Object();
        Object result = DexMemoryScopeKt.withConsumedDexBuffers(List.of(first, second), buffers -> {
            check(buffers.length == 2 && buffers[0].getInt(0) == 1 && buffers[1].getInt(0) == 2,
                  "DEX order changed");
            return token;
        });
        check(result == token && first.closes == 1 && second.closes == 1
              && first.unmaps == 1 && second.unmaps == 1, "successful load did not release inputs");
        check(SharedMemory.mapped.isEmpty(), "successful mapping retained");

        first = new SharedMemory(1); second = new SharedMemory(2);
        SharedMemory third = new SharedMemory(3);
        RuntimeException primary = new IllegalStateException("map failed");
        second.mapFailure = primary;
        AtomicBoolean loaderRan = new AtomicBoolean();
        try {
            DexMemoryScopeKt.withConsumedDexBuffers(List.of(first, second, third), buffers -> {
                loaderRan.set(true); return null;
            });
            throw new AssertionError("expected mapping failure");
        } catch (RuntimeException expected) { check(expected == primary, "mapping exception replaced"); }
        check(!loaderRan.get() && first.unmaps == 1 && second.maps == 0 && third.maps == 0,
              "partial DEX mapping reached the loader");
        check(first.closes == 1 && second.closes == 1 && third.closes == 1, "unused handles not closed");

        first = new SharedMemory(1); second = new SharedMemory(2);
        first.unmapFailure = new IllegalStateException("unmap failed");
        second.closeFailure = new IllegalStateException("close failed");
        RuntimeException loadFailure = new IllegalStateException("load failed");
        try {
            DexMemoryScopeKt.withConsumedDexBuffers(List.of(first, second), buffers -> { throw loadFailure; });
            throw new AssertionError("expected loader failure");
        } catch (RuntimeException expected) { check(expected == loadFailure, "loader exception replaced by cleanup"); }
        check(loadFailure.getSuppressed().length == 1
              && loadFailure.getSuppressed()[0] == first.unmapFailure
              && first.unmapFailure.getSuppressed()[0] == second.closeFailure,
              "cleanup exceptions not retained as suppressed");
        check(first.unmaps == 1 && second.unmaps == 1 && first.closes == 1 && second.closes == 1,
              "cleanup failure prevents later cleanup");
        check(SharedMemory.mapped.isEmpty(), "failed load retains mappings");
        System.out.println("PASS DEX inputs: ordering, success, partial-map failure, loader failure, all cleanup attempted");
    }

    public static void main(String[] args) throws Exception {
        queue(); dex();
        System.out.println("Checks: " + checks + "; transport/mapping are controlled host fixtures, not ART");
    }
}
