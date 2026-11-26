package io.jenkins.plugins.runtasksonagent.action;

import hudson.Extension;
import hudson.model.Computer;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Action without parameters: takes the agent offline, so it accepts no new builds. The build that triggered
 * it keeps running; an administrator brings the agent back online by hand.
 */
public class SetOfflineAction extends Action {

    @DataBoundConstructor
    public SetOfflineAction() {}

    /**
     * {@inheritDoc}
     *
     * <p>The agent stays connected, only temporarily offline. The reason shown on its page names the build.
     */
    @Override
    public void runAction(TaskContext context) {
        takeOffline(context);
    }

    /**
     * Marks the agent temporarily offline, with a reason naming the build that did it. Also used by the
     * reboot actions.
     *
     * @param context the build and the agent to take offline
     * @return the reason set on the agent
     */
    static OfflineCause takeOffline(TaskContext context) {
        Computer computer = context.computer();
        String message = "Taken offline by the Run Tasks on Agent plugin. Triggered by build '"
                + context.run().getFullDisplayName() + "'";
        OfflineCause cause = new OfflineCause.ByCLI(message);
        computer.setTemporaryOfflineCause(cause);
        return cause;
    }

    @Extension(ordinal = 60)
    public static final class DescriptorImpl extends TaskDescriptor<Action> {

        @Override
        public String getDisplayName() {
            return "Take agent offline";
        }
    }
}
