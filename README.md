# Async utils

This package implements some feature like AsyncManager and ManagerFutureTask.  
AsyncManager can be used to schedule job and run them in async way:

All other scheduled jobs will wa

```java
	asyncManager.schedule(/*some job*/)
		.next(/*some job after first job, i.e. Thread.sleep(1000)*/)
		.onDone(/*this callbacks means that all chain have been completed*/);
		
	asyncManager.schedule(/*some job, will be started when manager finish jobs above*/);
```

ManagerFutureTask can be used like Promises in JS - to run some async task with possibility to control when task is finished (and schedule next task).  
all ManagerFutureTask without AsyncManager will be executed in parallel.

Use case of Manager:   
we have 
* desktop app
* DB entity service:

```java
...
List findAll() {
	return asyncManager.schedule(() -> {
		return getEntityManager().createQuery("from " + getPersistentClass().getName(), persistentClass).getResultList();
	})
}
```

this give possibility to do async queries to DB wihout freezing UI in nice way:

```java
	someService.findAll().onDone(this::showResultsOnUI);
```

or if results needs to be proceessed in some other slow service:

```java
	someService.findAll().next(this::processResults).onDone(this::showResultsOnUI);
```

code above obtain results from service, process them and show on UI without freezing UI and all this would be done in async way