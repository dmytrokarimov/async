package com.smartfoxpro.util.async;

import java.util.function.Supplier;

public class AsyncTask<T> extends Thread{
	
	private T result;
	
	private Exception error;
	
	private final Supplier<T> job;
	
	private AsyncTask(Supplier<T> job) {
		setDaemon(true);
		this.job = job;
	}
	
	public static <T> AsyncTask<T> of(Supplier<T> job) {
		return new AsyncTask<>(job);
	}
	
	@Override
	public void run() {
		try {
			result = job.get();
		} catch (Exception e) {
			error = e;
		}
	}
	
	public T getResult() throws Exception {
		if (error != null) {
			throw error;
		}
		return result;
	}
	
	public Exception getError() {
		return error;
	}
}
