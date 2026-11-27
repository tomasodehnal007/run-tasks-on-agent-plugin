package io.jenkins.plugins.runtasksonagent.action;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import hudson.util.RemotingDiagnostics;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Action with a parameter: a Groovy script that runs <em>inside the agent's JVM</em> (like the
 * script console of the agent). Everything the script prints, and its result, goes into the build log.
 *
 * <p>It does not run on the built-in node: there the "agent's JVM" is the controller itself, and the script would
 * have full control of Jenkins.
 */
public class GroovyScriptAction extends Action {

    private final String scriptText;

    /**
     * @param scriptText the Groovy script, as typed into the form
     */
    @DataBoundConstructor
    public GroovyScriptAction(String scriptText) {
        this.scriptText = scriptText;
    }

    public String getScriptText() {
        return scriptText;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends the script to the agent, runs it there and writes its output into the build log.
     *
     * @throws AbortException if the build runs on the built-in node, or there is no connection to the agent
     * @throws IOException if the script cannot be sent to the agent
     * @throws InterruptedException if the thread is interrupted while waiting for the script
     */
    @Override
    public void runAction(TaskContext context) throws IOException, InterruptedException {
        Node node = context.computer().getNode();
        if (node instanceof Jenkins) {
            throw new AbortException("A Groovy script does not run on the built-in node, where it would run inside"
                    + " the Jenkins controller; use the script console of Jenkins for that");
        }
        VirtualChannel channel = context.launcher().getChannel();
        if (channel == null) {
            throw new AbortException("There is no channel to the agent");
        }
        String output = RemotingDiagnostics.executeGroovy(scriptText, channel);
        context.listener().getLogger().print(output);
    }

    @Extension(ordinal = 90)
    public static final class DescriptorImpl extends TaskDescriptor<Action> {

        @Override
        public String getDisplayName() {
            return "Run Groovy script on the agent";
        }
    }
}
