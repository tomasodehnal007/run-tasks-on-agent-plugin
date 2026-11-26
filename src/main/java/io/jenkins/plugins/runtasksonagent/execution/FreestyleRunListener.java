package io.jenkins.plugins.runtasksonagent.execution;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Environment;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.runtasksonagent.Phase;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

/**
 * Trigger for Freestyle builds: Jenkins calls this listener for every build of every job, and from
 * here we run the tasks configured on the agent the build runs on.
 *
 * <p>It is registered with a large negative {@code ordinal}, so that Jenkins calls it after all other run
 * listeners: the tasks run here can be disruptive or slow (cleaning the workspace, taking the agent offline,
 * waiting for a reboot), and other listeners may still need the agent or the workspace.
 *
 * <p>The only state is the timers of the during-build tasks, kept per build so that builds running
 * at the same time cannot touch each other's timers. The tasks of Pipeline builds are run by
 * {@link PipelineExecutorListener}; here a Pipeline build only waits for its POST_BUILD tasks to finish.
 */
@Extension(ordinal = -1000)
public class FreestyleRunListener extends RunListener<Run<?, ?>> {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /**
     * The longest time a finished Pipeline build waits for the POST_BUILD tasks of its {@code node} blocks; shorter
     * in tests. The default, 60 minutes, is longer than a graceful reboot can take (30 minutes waiting for other
     * builds plus 10 minutes waiting for the agent to reconnect). Set the system property
     * {@code io.jenkins.plugins.runtasksonagent.execution.FreestyleRunListener.nodeBlocksTimeout} (in minutes, or
     * with a unit such as {@code 90m}) to change it.
     */
    static Duration nodeBlocksTimeout = SystemProperties.getDuration(
            FreestyleRunListener.class.getName() + ".nodeBlocksTimeout", ChronoUnit.MINUTES, Duration.ofMinutes(60));

    /** Timers of the during-build tasks of the builds that are running now, by build. */
    private final Map<Run<?, ?>, List<ScheduledFuture<?>>> timers = new ConcurrentHashMap<>();

    /**
     * Called just before the build steps run: runs the PRE_BUILD entries of the agent, then starts the
     * timers of its DURING_BUILD entries.
     *
     * @param build the build that is about to run its steps
     * @param launcher starts processes on the agent
     * @param listener writes into the console log of the build
     * @return an environment that changes nothing
     * @throws Run.RunnerAbortedException (unchecked) if a task that is set to abort the build failed; Jenkins
     *     then stops the build and marks it as failed, and no during-build timers are started
     */
    @Override
    public Environment setUpEnvironment(AbstractBuild build, Launcher launcher, BuildListener listener) {
        Node node = build.getBuiltOn();
        Computer computer = node == null ? null : node.toComputer();
        if (computer == null) {
            return new Environment() {};
        }
        TaskListener tasksListener = ActionRunner.prefixed(listener, computer, build.getExecutor());
        TaskContext context = new TaskContext(build, computer, launcher, tasksListener, build.getWorkspace());
        if (new ActionRunner(context).run(Phase.PRE_BUILD)) {
            throw new Run.RunnerAbortedException();
        }
        List<ScheduledFuture<?>> futures = DuringBuildRunner.start(
                context, () -> new TaskContext(build, computer, launcher, tasksListener, build.getWorkspace()));
        if (!futures.isEmpty()) {
            timers.put(build, futures);
        }
        return new Environment() {};
    }

    /**
     * Called when the build is finished and its result is known: stops the timers of the build, then runs
     * the POST_BUILD entries of the agent.
     *
     * @param run the finished build
     * @param listener writes into the console log of the build
     */
    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        List<ScheduledFuture<?>> futures = timers.remove(run);
        if (futures != null) {
            futures.forEach(future -> future.cancel(true));
        }
        if (!(run instanceof AbstractBuild<?, ?> build)) {
            waitForNodeBlocks(run, listener);
            return;
        }
        Node node = build.getBuiltOn();
        Computer computer = node == null ? null : node.toComputer();
        if (computer == null) {
            return;
        }
        TaskListener tasksListener = ActionRunner.prefixed(listener, computer, build.getExecutor());
        TaskContext context = new TaskContext(
                build, computer, node.createLauncher(tasksListener), tasksListener, build.getWorkspace());
        new ActionRunner(context).run(Phase.POST_BUILD);
    }

    /**
     * Waits until the POST_BUILD tasks of all node blocks of the Pipeline build have finished, so that their output
     * still gets into the log of the build. Gives up after {@link #nodeBlocksTimeout}, so a stuck task cannot hold
     * this thread forever; output written by the tasks after that may be missing from the log.
     */
    private static void waitForNodeBlocks(Run<?, ?> run, TaskListener listener) {
        if (!nodeBlockRunning(run)) {
            return;
        }
        listener.getLogger().println("Waiting for the tasks after the node blocks to finish");
        long deadline = System.nanoTime() + nodeBlocksTimeout.toNanos();
        try {
            while (nodeBlockRunning(run)) {
                if (System.nanoTime() - deadline > 0) {
                    listener.getLogger()
                            .println("Gave up waiting for the tasks after the node blocks after " + nodeBlocksTimeout
                                    + "; their further output is not in this log");
                    return;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True if some executor is still running a node block of the Pipeline build. */
    private static boolean nodeBlockRunning(Run<?, ?> run) {
        return Arrays.stream(Jenkins.get().getComputers())
                .flatMap(computer -> computer.getExecutors().stream())
                .map(Executor::getCurrentExecutable)
                .anyMatch(executable -> executable != null && executable.getParentExecutable() == run);
    }
}
