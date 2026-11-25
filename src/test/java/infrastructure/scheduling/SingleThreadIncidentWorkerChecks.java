package infrastructure.scheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static support.Checks.check;
import static support.Checks.expect;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class SingleThreadIncidentWorkerChecks {
    public static void main(String[] args) throws Exception {
        runsInSubmissionOrderOnOneThread();
        idleThreadExitsAndIsReplaced();
        closeDrainsQueueAndRejectsNewWork();
        System.out.println("UC3 incident worker checks passed");
    }

    private static void runsInSubmissionOrderOnOneThread() throws Exception {
        try (SingleThreadIncidentWorker worker = new SingleThreadIncidentWorker()) {
            List<Integer> order = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            List<Boolean> recognised = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) {
                int index = i;
                worker.execute(() -> {
                    order.add(index);
                    threads.add(Thread.currentThread());
                    recognised.add(worker.isWorkerThread());
                    done.countDown();
                });
            }
            check(done.await(5, TimeUnit.SECONDS), "Submitted operations run");
            check(order.equals(List.of(0, 1, 2, 3, 4)), "Operations run in submission order");
            check(threads.stream().distinct().count() == 1 && !threads.get(0).isDaemon(),
                    "Operations share one non-daemon thread, so an accepted write can finish");
            check(recognised.stream().allMatch(Boolean::booleanValue) && !worker.isWorkerThread(),
                    "Only the worker thread is recognised as the worker");
        }
    }

    private static void idleThreadExitsAndIsReplaced() throws Exception {
        try (SingleThreadIncidentWorker worker = new SingleThreadIncidentWorker(Duration.ofMillis(50))) {
            Thread first = runAndReturnThread(worker);
            first.join(5_000);
            check(!first.isAlive(), "An idle worker thread exits, so a forgotten close cannot keep the JVM alive");
            FutureTask<Boolean> later = new FutureTask<>(worker::isWorkerThread);
            worker.execute(later);
            check(later.get(5, TimeUnit.SECONDS), "A replacement thread is still recognised as the worker");
        }
    }

    private static void closeDrainsQueueAndRejectsNewWork() throws Exception {
        SingleThreadIncidentWorker worker = new SingleThreadIncidentWorker();
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> blocking = new FutureTask<>(() -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        });
        FutureTask<String> queued = new FutureTask<>(() -> "finished");
        worker.execute(blocking);
        worker.execute(queued);
        worker.close();
        release.countDown();
        check(queued.get(5, TimeUnit.SECONDS).equals("finished"), "Closing lets queued operations finish");
        expect(RejectedExecutionException.class, () -> worker.execute(() -> { }),
                "a closed worker accepted new work");
    }

    private static Thread runAndReturnThread(SingleThreadIncidentWorker worker) throws Exception {
        FutureTask<Thread> task = new FutureTask<>(Thread::currentThread);
        worker.execute(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
