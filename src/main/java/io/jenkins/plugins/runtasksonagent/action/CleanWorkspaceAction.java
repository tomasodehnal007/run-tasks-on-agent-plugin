package io.jenkins.plugins.runtasksonagent.action;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.slaves.WorkspaceList;
import io.jenkins.plugins.runtasksonagent.Phase;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Action without parameters: deletes everything in the workspace of the build on the agent, the workspace
 * directory itself stays. It is not offered during the build, where it would pull the files out from under the
 * running build steps.
 */
public class CleanWorkspaceAction extends Action {

    @DataBoundConstructor
    public CleanWorkspaceAction() {}

    /**
     * {@inheritDoc}
     *
     * <p>Works before the build too, when the workspace is not allocated yet: then the default location
     * of the workspace of the job is cleaned (see {@link TaskContext#workspaceOrDefault()}). Another build of the
     * same job may be using that directory at the time (concurrent builds), so it is cleaned only while holding
     * Jenkins' lease on it, as a build would; if it is in use, it is left alone. A workspace that does not exist
     * is not an error. In a Pipeline build the workspace is not known, so nothing is cleaned.
     *
     * @throws AbortException if the workspace is not known, as in a Pipeline build
     * @throws IOException if a file cannot be deleted
     * @throws InterruptedException if the thread is interrupted while deleting
     */
    @Override
    public void runAction(TaskContext context) throws IOException, InterruptedException {
        FilePath allocated = context.workspace();
        if (allocated != null) {
            // The build has allocated it and holds the lease.
            allocated.deleteContents();
            return;
        }
        FilePath workspace = context.workspaceOrDefault();
        if (workspace == null) {
            throw new AbortException("The workspace is not known (not supported in Pipeline builds)");
        }
        Computer computer = context.computer();
        if (computer == null) {
            throw new AbortException("The agent is not known");
        }
        // allocate() does not wait: it leases the directory if it is free, otherwise the next free "@2" one.
        try (WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(workspace)) {
            if (!lease.path.equals(workspace)) {
                context.listener()
                        .getLogger()
                        .println("The workspace " + workspace.getRemote()
                                + " is in use by another build, it is not cleaned");
                return;
            }
            workspace.deleteContents();
        }
    }

    @Extension(ordinal = 80)
    public static final class DescriptorImpl extends TaskDescriptor<Action> {

        @Override
        public String getDisplayName() {
            return "Clean workspace";
        }

        /**
         * {@inheritDoc}
         *
         * @return {@code false} during the build, {@code true} before and after it
         */
        @Override
        public boolean isApplicable(Phase phase) {
            return phase != Phase.DURING_BUILD;
        }
    }
}
