package use_case.shared;

import java.util.concurrent.Executor;

/**
 * Runs incident operations from investigation (UC3) and recovery (UC4) one at a time, in submission order,
 * on a single thread, as IncidentRepository requires. The composition root creates the worker, shares it
 * between use cases and shuts it down; the use cases only submit work to it.
 * After shutdown, execute throws RejectedExecutionException.
 */
public interface IncidentWorker extends Executor {
    /** True when called from the worker thread, where a nested incident call must run inline. */
    boolean isWorkerThread();
}
