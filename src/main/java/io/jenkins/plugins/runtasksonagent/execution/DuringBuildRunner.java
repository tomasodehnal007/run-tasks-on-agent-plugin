package io.jenkins.plugins.runtasksonagent.execution;

import hudson.security.ACL;
import hudson.util.ClassLoaderSanityThreadFactory;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.runtasksonagent.Phase;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.entry.BuildEntry;
import io.jenkins.plugins.runtasksonagent.entry.DuringBuildEntry;
import io.jenkins.plugins.runtasksonagent.execution.ActionRunner.EntryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.security.ImpersonatingScheduledExecutorService;
import jenkins.util.SystemProperties;

/**
 * Schedules the {@link Phase#DURING_BUILD} entries: each one is checked periodically on a timer thread
 * until the caller cancels the returned futures (when the build ends).
 *
 * <p>All state ("already performed") lives in the objects created by one call of {@link #start}, so it is
 * separate for every build. Nothing is kept in the configuration or in a shared field.
 *
 * <p>The checks run on a thread pool of their own, not on Jenkins' shared {@code jenkins.util.Timer}: a check can run
 * a script for a long time, and many of them would otherwise use up the threads Jenkins needs for its own periodic
 * work.
 */
final class DuringBuildRunner {

    private static final Logger LOGGER = Logger.getLogger(DuringBuildRunner.class.getName());

    /**
     * How many checks can run at the same time, over all builds; 10 by default, changed by the system property
     * {@code io.jenkins.plugins.runtasksonagent.execution.DuringBuildRunner.threads}. When all threads are busy, the
     * next checks wait.
     */
    private static final int THREADS =
            Math.max(1, SystemProperties.getInteger(DuringBuildRunner.class.getName() + ".threads", 10));

    /** Runs the checks as the system user, like {@code jenkins.util.Timer} does. */
    private static final ScheduledExecutorService EXECUTOR = new ImpersonatingScheduledExecutorService(
            new ScheduledThreadPoolExecutor(
                    THREADS,
                    new NamingThreadFactory(
                            new ClassLoaderSanityThreadFactory(new DaemonThreadFactory()),
                            "RunTasksOnAgent.DuringBuild")),
            ACL.SYSTEM2);

    private DuringBuildRunner() {}

    /**
     * Starts a timer for every during-build entry configured on the agent. The first check of an entry
     * comes after one interval; the start of the build is covered by the before-build entries. Each next
     * check comes one interval after the previous one has finished, so a slow check never runs back to back.
     *
     * @param first a context used only to find the entries of the agent
     * @param contexts creates a fresh context for every check, so that e.g. the workspace is found once
     *     Jenkins has allocated it
     * @return the scheduled checks; the caller must cancel them when the build has ended
     */
    static List<ScheduledFuture<?>> start(TaskContext first, Supplier<TaskContext> contexts) {
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        for (BuildEntry entry : new ActionRunner(first).entriesOf(Phase.DURING_BUILD)) {
            if (!(entry instanceof DuringBuildEntry duringEntry)) {
                continue; // another entry type, it has no interval
            }
            long period =
                    duringEntry.getUnit() == null ? 0 : duringEntry.getUnit().toMillis(duringEntry.getInterval());
            if (period <= 0) {
                first.listener()
                        .getLogger()
                        .println(
                                Phase.DURING_BUILD + ": skipping a task without a valid interval, it is never checked");
                continue;
            }
            Runnable check = checkOf(duringEntry, contexts);
            futures.add(EXECUTOR.scheduleWithFixedDelay(check, period, period, TimeUnit.MILLISECONDS));
        }
        return futures;
    }

    /** Creates one check; the flag "the action has already succeeded" belongs to it alone. */
    private static Runnable checkOf(DuringBuildEntry entry, Supplier<TaskContext> contexts) {
        AtomicBoolean performed = new AtomicBoolean(false);
        return () -> {
            if (!entry.isRepeat() && performed.get()) {
                return;
            }
            try {
                EntryResult result = new ActionRunner(contexts.get()).runEntry(entry);
                if (result == EntryResult.SUCCEEDED) {
                    performed.set(true);
                }
            } catch (RuntimeException e) {
                // An exception escaping a scheduled task would silently stop all later checks.
                LOGGER.log(Level.WARNING, "Check of a during-build task failed", e);
            }
        };
    }
}
