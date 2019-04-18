package com.smartfoxpro.util.async;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AsyncManagerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncManagerTest.class);
    private static final int SLEEP_TIME = 50;
    private AsyncManager asyncManager;

    @Before
    public void init() {
        asyncManager = new AsyncManager();
    }

    @Test
    public void shouldPerformJobAsynchronously() {
        long beforeJob = System.currentTimeMillis();
        asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            return true;
        });
        long afterJob = System.currentTimeMillis();
        long expected = afterJob - beforeJob;

        assertTrue(expected < SLEEP_TIME);
    }

    @Test
    public void shouldPerformJobAsynchronouslyWithExtraJob() throws ExecutionException, InterruptedException {
        long beforeJob = System.currentTimeMillis();
        asyncManager.schedule(() -> {
            Thread.sleep(SLEEP_TIME);
            return true;
        }, () -> {
            long afterJob = System.currentTimeMillis();
            long expected = afterJob - beforeJob;

            assertTrue(expected < SLEEP_TIME);
        }).get();
    }

    @Test()
    public void shouldPerformJobAsynchronouslyWhenEndCallbackIsNull() throws ExecutionException, InterruptedException {
        Exception exception = new InterruptedException();
        asyncManager.schedule(() -> {
            throw exception;
        }, null, error -> assertEquals(exception, error)).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyWhenSomeCallbackIsNull() throws ExecutionException, InterruptedException {
        long beforeJob = System.currentTimeMillis();
        asyncManager.schedule(() -> {
            long afterJob = System.currentTimeMillis();
            long expected = afterJob - beforeJob;

            assertTrue(expected < SLEEP_TIME);
        }).get();
    }

    @Test
    public void shouldPerformJobAsynchronouslyInOrder() {
        AtomicBoolean flag = new AtomicBoolean(false);
        asyncManager.schedule(() -> flag.set(true));

        asyncManager.schedule(() -> assertTrue(flag.get()));
    }

    @Test(expected = Exception.class)
    public void shouldThrowExceptionWhenJobPerformWithError() throws ExecutionException, InterruptedException {
        Exception exception = new InterruptedException();
        try {
            asyncManager.schedule(() -> {
                throw exception;
            }).onErr(error -> LOGGER.error(error.getMessage(), error)).get();
        } catch (InterruptedException e) {
            Thread.sleep(SLEEP_TIME);
        }
    }
}
