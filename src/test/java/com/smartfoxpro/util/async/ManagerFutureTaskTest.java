package com.smartfoxpro.util.async;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ManagerFutureTaskTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagerFutureTaskTest.class);
    private static final int SLEEP_TIME = 100;
    private static final List<Integer> INTEGERS = new ArrayList<>(Arrays.asList(1, 2, 3));
    private AsyncManager asyncManager;

    @Before
    public void init() {
        asyncManager = new AsyncManager();
    }

    @Test(expected = Exception.class)
    public void shouldThrowExceptionWhenGetTaskQuietlyWithError() {
        Exception exception = new IllegalStateException();
        asyncManager.schedule(() -> {
            throw exception;
        }).getQuietly();
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndOnDoneRunnable() throws ExecutionException, InterruptedException {
        long beforeJob = System.currentTimeMillis();
        asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            return true;
        }, () -> {
            long afterJob = System.currentTimeMillis();
            long expected = afterJob - beforeJob;

            assertTrue(expected < SLEEP_TIME);
        }).onDone(() -> LOGGER.debug(Thread.currentThread().getName())).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndOnDoneConsumers() throws ExecutionException, InterruptedException {
        long beforeJob = System.currentTimeMillis();
        asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            return true;
        }).onDone(ex -> LOGGER.debug(Thread.currentThread().getName()), (b) -> {
            AtomicBoolean atomicBoolean = new AtomicBoolean(b);
            atomicBoolean.set(true);
            long afterJob = System.currentTimeMillis();
            long expected = afterJob - beforeJob;

            assertTrue(expected < SLEEP_TIME);
        }).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndDoNext() throws ExecutionException, InterruptedException {
        AtomicInteger atomicInteger = new AtomicInteger();
        asyncManager.schedule(() -> {
            atomicInteger.set(1);
            try {
                Thread.sleep(SLEEP_TIME);
            } catch (InterruptedException e) {
                LOGGER.error(e.getMessage(), e);
            }
            return atomicInteger.get();
        }).next(() -> {
            atomicInteger.set(2);
            assertEquals(2, atomicInteger.get());
            return true;
        }).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndDoNextFuture() throws ExecutionException, InterruptedException {
        AtomicBoolean flag = new AtomicBoolean(true);

        asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            flag.set(false);
            LOGGER.info("First value by order: " + 0);
            return true;
        }).nextFuture((value) -> asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            flag.set(true);
            LOGGER.info("Second value by order: " + 1);
            assertTrue(flag.get());
            return true;
        })).get();

        asyncManager.schedule(() -> {
        }).onDone(() -> LOGGER.info("Third value by order: " + 2)).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndIterateOver() {
        List<Integer> integers = new ArrayList<>(Arrays.asList(1, 2, 3));

        ManagerFutureTask.iterateOver(
                asyncManager.schedule(() -> INTEGERS),
                (value) -> asyncManager.schedule(() -> INTEGERS.add(value)))
                .onDone(result -> assertEquals(integers, INTEGERS));
    }

    @Test
    public void shouldPerformJobAsynchronouslyAndIterateOverWithNullAsyncManager() {
        List<Integer> integers = new ArrayList<>(Arrays.asList(1, 2, 3));

        ManagerFutureTask.iterateOver(asyncManager, (r) -> LOGGER.debug(Thread.currentThread().getName()), INTEGERS,
                (value) -> asyncManager.schedule(() -> INTEGERS.add(value)))
                .onDone(result -> assertEquals(integers, INTEGERS));
    }

}
