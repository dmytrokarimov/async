package com.smartfoxpro.util.async;

/**
 * Runs new deamon thread and execute runnable job
 * @author Dmytro Karimov
 */
public class AsyncJob extends Thread{

	private Exception error;
	
	private final Runnable job;
	
	private AsyncJob(Runnable job) {
		setDaemon(true);
		this.job = job;
	}
	
	public static AsyncJob of(Runnable job) {
		return new AsyncJob(job);
	}
	
	public static void startJob(Runnable job) {
		AsyncJob.of(job).start();
	}
	
	@Override
	public void run() {
		try {
			job.run();
		} catch (Exception e) {
			error = e;
		}
	}
	
	public Exception getError() {
		return error;
	}
}
