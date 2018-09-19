package net.util.async;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager can be used to schedule job and run them in async way.
 * Each job scheduled via {@link #schedule(Callable)} methods will be executed only if previous job ended up
 *
 * @author Dmytro Karimov
 */
public class AsyncManager {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncManager.class);

    private static final int QUEUE_CAPACITY = 10_000;

    //Throwable needed to show full stacktrace to schedule caller
    private final BlockingQueue<Entry<ManagerFutureTask<?>, Throwable>> queue;

    private final AsyncJobProcessor processThread;

    private final Consumer<Runnable> executor;

    /**
     * Creates new thread for each callback
     */
    public AsyncManager() {
        this(AsyncJob::startJob);
    }

    /**
     * @param executor implements how manager should handle callbacks, i.e. create new thread for each callback
     */
    public AsyncManager(Consumer<Runnable> executor) {
        queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        processThread = new AsyncJobProcessor();
        processThread.start();

        this.executor = executor;
    }

    Optional<Entry<ManagerFutureTask<?>, Throwable>> removeTask(ManagerFutureTask<?> task) {
        return queue.stream()
                .filter(entry -> Objects.equals(entry.getKey(), task))
                .findFirst()
                .map(entry -> {
                    queue.remove(entry);

                    return entry;
                });
    }

    AsyncJobProcessor getProcessThread() {
        return processThread;
    }

    static boolean isManagerThread() {
        return Thread.currentThread() instanceof AsyncJobProcessor;
    }


    protected <T> ManagerFutureTask<T> schedule(Callable<T> job, ManagerFutureTask<?> parentFuture) {
        return schedule(job, null, null, parentFuture);
    }

    protected ManagerFutureTask<?> schedule(Runnable job, ManagerFutureTask<?> parentFuture) {
        return schedule(Executors.callable(job, null), null, null, parentFuture);
    }

    protected <T> ManagerFutureTask<T> schedule(Callable<T> job, Runnable endCallback,
                                                Consumer<Throwable> errorCallback,
                                                ManagerFutureTask<?> parentFuture) {
        Callable<T> task = () -> {
            CountDownLatch lock = new CountDownLatch(1 + (Objects.nonNull(endCallback) ? 1 : 0));
            T value = tryJob(lock, job, endCallback, errorCallback);
            try {
                lock.await();
            } catch (InterruptedException e) {
                LOG.error(e.getMessage(), e);
            }
            return value;
        };


        ManagerFutureTask<T> future = new ManagerFutureTask<>(task, executor, this, parentFuture);

        Entry<ManagerFutureTask<?>, Throwable> jobEntry = new SimpleEntry<>(future, new Throwable());
        if (!queue.offer(jobEntry)) {
            AsyncJob.of(() -> {
                try {
                    queue.put(jobEntry);
                } catch (InterruptedException e) {
                    LOG.error(e.getMessage(), e);
                }
            }).start();
        }

        return future;
    }


    /**
     * Run async job. All other scheduled jobs will wait until current job (and all child future) won't be ended
     *
     * @param endCallback   can be null, will be run by executor when job ended
     * @param errorCallback can be null, will be run by executor when job failed with an error
     * @return future that can control job execution
     */
    public <T> ManagerFutureTask<T> schedule(Callable<T> job, Runnable endCallback, Consumer<Throwable> errorCallback) {
        return schedule(job, endCallback, errorCallback, null);
    }

    private void tryJob(CountDownLatch lock, Runnable job, Consumer<Throwable> errorCallback) {
        tryJob(lock, Executors.callable(job, null), null, errorCallback);
    }

    /**
     * try to execute job and obtain a result. Callbacks are needed to control errors and finish of job execution
     */
    private <T> T tryJob(CountDownLatch lock, Callable<T> job, Runnable end, Consumer<Throwable> errorCallback) {
        T value = null;
        try {
            value = job.call();

            if (Objects.nonNull(end)) {
                tryJob(lock, () -> executor.accept(() -> tryJob(lock, end, errorCallback)), errorCallback);
            } else {
                lock.countDown();
            }
        } catch (Throwable e) {
            if (Objects.nonNull(errorCallback)) {
                tryJob(lock, () -> executor.accept(() ->
                        tryJob(lock, () -> errorCallback.accept(e), null)), null);
            } else {
                //no err callbacks -> decrement to zero
                while (lock.getCount() > 0) {
                    lock.countDown();
                }
                LOG.error(e.getMessage(), e);
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        return value;
    }

    /**
     * Run async job. All other scheduled jobs will wait until current (and all child future) won't be ended
     *
     * @param endCallback can be null, will be run by executor when job ended
     * @return future that can control job execution
     */
    public <T> ManagerFutureTask<T> schedule(Callable<T> job, Runnable endCallback) {
        return schedule(job, endCallback, null);
    }

    /**
     * Run async job. All other scheduled jobs will wait until current (and all child future) won't be ended
     *
     * @return future that can control job execution
     */
    public <T> ManagerFutureTask<T> schedule(Callable<T> job) {
        return schedule(job, null, null);
    }

    public ManagerFutureTask<?> schedule(Runnable job) {
        return schedule(Executors.callable(job, null), null, null);
    }

    class AsyncJobProcessor extends Thread {

        AsyncJobProcessor() {
            setName("AsyncJobProcessor");
            setDaemon(true);
        }

        void runTask(ManagerFutureTask<?> task, Throwable callerStacktrace) {
            try {
                task.run();
                task.get();
            } catch (Throwable e) {
                e.addSuppressed(callerStacktrace);
                task.callOnErrListeners(e);
                LOG.error(e.getMessage(), e);
            }
        }

        @Override
        public void run() {
            try {
                Entry<ManagerFutureTask<?>, Throwable> jobEntry;
                while ((jobEntry = queue.take()) != null) {
                    runTask(jobEntry.getKey(), jobEntry.getValue());
                }
            } catch (InterruptedException e) {
                LOG.error(e.getMessage(), e);
            }
        }
    }
}
