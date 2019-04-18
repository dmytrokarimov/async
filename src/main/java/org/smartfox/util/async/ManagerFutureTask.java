package org.smartfox.util.async;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implement control over async job. Can be used for scheduling next async job AFTER current via {@link ManagerFutureTask#next} methods
 * @author Dmytro Karimov
 */
public class ManagerFutureTask<T> extends FutureTask<T> {

    private final AsyncManager asyncManager;
    private final Consumer<Runnable> executor;
    private final List<Consumer<T>> doneCallbacks = new ArrayList<>();
    private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();
    private ManagerFutureTask<?> parentFuture;
    private ManagerFutureTask<?> childFuture;
    private volatile Throwable lastError;

    public ManagerFutureTask(Callable<T> callable, Consumer<Runnable> executor, AsyncManager asyncManager,
                             ManagerFutureTask<?> parentFuture) {
        this(callable, executor, asyncManager);
        this.parentFuture = parentFuture;
    }

    public ManagerFutureTask(Callable<T> callable, Consumer<Runnable> executor, AsyncManager asyncManager) {
        super(callable);
        this.executor = executor;
        this.asyncManager = asyncManager;
    }

    public ManagerFutureTask(Runnable runnable, T result, Consumer<Runnable> executor, AsyncManager asyncManager) {
        this(Executors.callable(runnable, result), executor, asyncManager);
    }

    /**
     * works the same as {@link ManagerFutureTask#get()}, all exceptions would be wrapped to IllegalStateException
     */
    public T getQuietly() {
        try {
            return super.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        try {
            return super.get();
        } catch (Throwable e) {
            lastError = e;
            throw e;
        }
    }

    /**
     * Async iteration over collection. All items will be processed in sequence
     *
     * @param executor needed to run callbacks
     * @param task     applies for each element, returned result can be obtain in returned future
     * @return future with results of all tasks
     */
    public static <E, T> ManagerFutureTask<Collection<T>> syncIterateOver(Consumer<Runnable> executor,
                                                                          Collection<E> collection,
                                                                          Function<E, ManagerFutureTask<T>> task) {
        return iterateOver(null, executor, collection, null, task);
    }

    /**
     * Async iteration over collection. All items will be processed in sequence
     *
     * @param asyncManager needed to run futureTask in async manners, iterateOver would be sync if param is null
     * @param executor     needed to run callbacks
     * @param task         applies for each element, returned result can be obtain in returned future
     * @return future with results of all tasks
     */
    public static <E, T> ManagerFutureTask<Collection<T>> iterateOver(AsyncManager asyncManager,
                                                                      Consumer<Runnable> executor,
                                                                      Collection<E> collection,
                                                                      Function<E, ManagerFutureTask<T>> task) {
        return iterateOver(asyncManager, executor, collection, null, task);
    }

    @Override
    public void run() {
        if (AsyncManager.isManagerThread() || Objects.isNull(asyncManager)) {
            super.run();
        } else {
            asyncManager.schedule(super::run, this);
        }
    }

    /**
     * Async iteration over collection. All items will be processed in sequence
     *
     * @param onEmpty      will be called if returned collection is empty
     * @param asyncManager needed to run futureTask in async manners, iterateOver would be sync if param is null
     * @param executor     needed to run callbacks
     * @param task         applies for each element, returned result can be obtain in returned future
     * @return future with results of all tasks
     */
    private static <E, T> ManagerFutureTask<Collection<T>> iterateOver(AsyncManager asyncManager,
                                                                       Consumer<Runnable> executor,
                                                                       Collection<E> collection,
                                                                       Runnable onEmpty,
                                                                       Function<E, ManagerFutureTask<T>> task) {
        ManagerFutureTask<Collection<E>> future = new ManagerFutureTask<>(() -> collection, executor, asyncManager);
        future.run();

        return iterateOver(future, onEmpty, task);
    }

    /**
     * Async iteration over collection returned by param collectionFuture. All items will be processed in sequence
     *
     * @param collectionFuture task that return collection for iterations
     * @param task             applies for each element, returned result can be obtain in returned future
     * @return future with results of all tasks
     */
    public static <E, T> ManagerFutureTask<Collection<T>> iterateOver(ManagerFutureTask<? extends
            Collection<E>> collectionFuture, Function<E, ManagerFutureTask<T>> task) {
        return iterateOver(collectionFuture, null, task);
    }

    /**
     * Async iteration over collection returned by param collectionFuture. All items will be processed in sequence
     *
     * @param collectionFuture task that return collection for iterations
     * @param onEmpty          will be called if returned collection is empty
     * @param task             applies for each element, returned result can be obtain in returned future
     * @return future with results of all tasks
     */
    private static <E, T> ManagerFutureTask<Collection<T>> iterateOver(ManagerFutureTask<? extends
            Collection<E>> collectionFuture, Runnable onEmpty, Function<E, ManagerFutureTask<T>> task) {
        return collectionFuture.next(collection -> {
            if (Objects.nonNull(onEmpty) && (Objects.isNull(collection) || collection.isEmpty())) {
                collectionFuture.executor.accept(onEmpty);

                return Collections.emptyList();
            }

            return collection
                    .stream()
                    .map(task)
                    .map(ManagerFutureTask::getQuietly)
                    .collect(Collectors.toList());
        });
    }

    /**
     * Runs async job after this future. All other future that managed by current AsyncManager will wait until
     * all nextFuture() calls will be finished
     */
    public <E> ManagerFutureTask<E> nextFuture(Function<T, ? extends ManagerFutureTask<E>> job) {
        Callable<E> task = () -> {
            ManagerFutureTask<E> future = job.apply(this.get());

            future.parentFuture = this;

            //this means future.run won't be called, because async manager blocked by this future
            if (Objects.equals(future.asyncManager, this.asyncManager) && Objects.nonNull(this.asyncManager)) {
                //let's take control of this future and run it
                asyncManager.removeTask(future).ifPresent(entry ->
                        asyncManager.getProcessThread().runTask(future, entry.getValue()));
            }

            return future.get();
        };

        return nextTask(task);
    }

    /**
     * Runs async job after this future. All other future that managed by current AsyncManager will wait until
     * all next() calls will be finished
     */
    public <E> ManagerFutureTask<E> next(Callable<E> job) {
        return nextTask(() -> {
            this.get();
            return job.call();
        });
    }

    private <E> ManagerFutureTask<E> nextTask(Callable<E> task) {
        ManagerFutureTask<E> childTask;

        if (!isDone()) {
            childFuture = childTask = new ManagerFutureTask<>(task, executor, asyncManager, this);
        } else {
            childTask = asyncManager.schedule(task, this);
        }


        return childTask;
    }

    /**
     * Runs async job after this future. All other future that managed by current AsyncManager will wait until
     * all next() calls will be finished
     */
    public <E> ManagerFutureTask<E> next(Function<T, E> job) {
        return nextTask(() -> job.apply(this.get()));
    }

    public ManagerFutureTask<T> onDone(Runnable job) {
        return onDone(v -> job.run());
    }

    private void acceptResult(Consumer<T> job) {
        try {
            job.accept(get());
        } catch (InterruptedException | ExecutionException e) {
            callOnErrListeners(e);
        }
    }

    @Override
    protected void setException(Throwable t) {
        super.setException(t);

        callOnErrListeners(t);
    }

    void callOnErrListeners(Throwable err) {
        lastError = err;

        if (Objects.nonNull(parentFuture)) {
            parentFuture.callOnErrListeners(err);
        }

        if (!errorCallbacks.isEmpty()) {
            executor.accept(() -> {
                synchronized (errorCallbacks) {
                    errorCallbacks.forEach(cb -> cb.accept(err.getCause()));

                    //to prevent double calling
                    errorCallbacks.clear();
                }
            });
        }
    }

    public ManagerFutureTask<T> onErr(Consumer<Throwable> job) {
        synchronized (errorCallbacks) {
            errorCallbacks.add(job);
        }

        if (Objects.nonNull(lastError)) {
            callOnErrListeners(lastError);
        } else {
            ManagerFutureTask<?> parent = parentFuture;
            while (Objects.nonNull(parent) && Objects.isNull(lastError)) {
                lastError = parent.lastError;
                parent = parent.parentFuture;
            }
            if (Objects.nonNull(lastError)) {
                callOnErrListeners(lastError);
            }
        }

        return this;
    }

    /**
     * @param job will be executed with value returned by {@link #get()}.
     *            Job won't be executed in case if {@link #isCancelled()}
     */
    public ManagerFutureTask<T> onDone(Consumer<T> job) {
        if (isDone() && !isCancelled()) {
            acceptResult(value -> executor.accept(() -> job.accept(value)));
        } else {
            synchronized (doneCallbacks) {
                doneCallbacks.add(job);
            }
        }
        return this;
    }

    public ManagerFutureTask<T> onDone(Consumer<Runnable> executor, Consumer<T> job) {
        return onDone(result -> executor.accept(() -> job.accept(result)));
    }

    @Override
    protected void done() {
        if (Objects.nonNull(childFuture)) {
            childFuture.onDone(this::runOnDone);

            if (Objects.nonNull(asyncManager)) {
                asyncManager.getProcessThread().runTask(childFuture, new Throwable());
            } else {
                childFuture.run();
                try {
                    childFuture.get();
                } catch (InterruptedException | ExecutionException ignored) {
                }
            }

        } else {
            runOnDone();
        }
    }

    private void runOnDone() {
        synchronized (doneCallbacks) {
            executor.accept(() -> doneCallbacks.forEach(this::acceptResult));
        }
    }
}
