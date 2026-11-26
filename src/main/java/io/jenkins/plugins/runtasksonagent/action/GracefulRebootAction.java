package io.jenkins.plugins.runtasksonagent.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.runtasksonagent.Phase;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.util.SystemProperties;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Like {@link RebootAction}, but after taking the agent offline (no new builds) it first waits until the
 * other builds running on the agent have finished, and only then reboots. Only offered after the build.
 *
 * <p>The wait is limited, so a stuck build cannot block this one forever. It has the same
 * "Reboot command" field as {@link RebootAction}, whose form it shares.
 *
 * <p>Only one build reboots an agent at a time: if builds running at the same time on the agent all want a
 * graceful reboot, the first one reboots it and the others skip theirs, instead of waiting for each other.
 */
public class GracefulRebootAction extends RebootAction {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    /**
     * How long to wait for the other builds; 30 minutes by default, changed by the system property
     * {@code io.jenkins.plugins.runtasksonagent.action.GracefulRebootAction.maxWait} (in minutes, or with a unit
     * such as {@code 45m}).
     */
    private static final Duration MAX_WAIT = SystemProperties.getDuration(
            GracefulRebootAction.class.getName() + ".maxWait", ChronoUnit.MINUTES, Duration.ofMinutes(30));

    /** Names of the agents a graceful reboot is in progress on. */
    private static final Set<String> REBOOTING = ConcurrentHashMap.newKeySet();

    /**
     * @param command the command that reboots the machine, or {@code null} or blank for the default
     *     of the agent's operating system
     */
    @DataBoundConstructor
    public GracefulRebootAction(@CheckForNull String command) {
        super(command);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Does nothing if another build is already rebooting the agent gracefully: the agent is going to reboot
     * anyway, and this build would otherwise keep that build waiting until it gives up.
     */
    @Override
    public void runAction(TaskContext context) throws IOException, InterruptedException {
        String agent = context.computer().getName();
        if (!REBOOTING.add(agent)) {
            context.listener().getLogger().println("Another build is already rebooting the agent, skipping the reboot");
            return;
        }
        try {
            super.runAction(context);
        } finally {
            REBOOTING.remove(agent);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Waits until no other executor of the agent is busy. The executor running this build is not
     * counted, otherwise the action would wait for itself.
     *
     * @throws AbortException if other builds are still running after the time limit (30 minutes by default)
     */
    @Override
    protected void waitBeforeReboot(TaskContext context) throws IOException, InterruptedException {
        context.listener().getLogger().println("Waiting for the other builds on the agent to finish");
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (otherBuildsRunning(context.computer())) {
            if (System.nanoTime() - deadline > 0) {
                context.computer()
                        .setTemporaryOfflineCause(
                                new OfflineCause.ByCLI("Reboot not done: other builds were still running after "
                                        + MAX_WAIT.toMinutes() + " minutes"));
                throw new AbortException("Other builds are still running on the agent, giving up the reboot");
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    /** True if some executor of the agent other than the one running this code is busy. */
    private static boolean otherBuildsRunning(Computer computer) {
        Executor own = Executor.currentExecutor();
        for (Executor executor : computer.getExecutors()) {
            if (executor != own && executor.isBusy()) {
                return true;
            }
        }
        return false;
    }

    @Extension(ordinal = 40)
    public static final class DescriptorImpl extends TaskDescriptor<Action> {

        @Override
        public String getDisplayName() {
            return "Reboot the agent's machine when other builds have finished";
        }

        /**
         * {@inheritDoc}
         *
         * @return {@code true} only after the build
         */
        @Override
        public boolean isApplicable(Phase phase) {
            return phase == Phase.POST_BUILD;
        }
    }
}
