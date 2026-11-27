package io.jenkins.plugins.runtasksonagent.action;

import hudson.AbortException;
import hudson.Extension;
import hudson.remoting.VirtualChannel;
import hudson.util.RemotingDiagnostics;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import io.jenkins.plugins.runtasksonagent.util.ScriptApprovals;
import java.io.IOException;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Action with a parameter: a Groovy script that runs <em>inside the agent's JVM</em> (like the
 * script console of the agent). Everything the script prints, and its result, goes into the build log.
 *
 * <p>The script runs only once an administrator has approved it (see {@link ScriptApprovals}).
 */
public class GroovyScriptAction extends Action {

    private final String scriptText;

    /**
     * @param scriptText the Groovy script, as typed into the form
     */
    @DataBoundConstructor
    public GroovyScriptAction(String scriptText) {
        this.scriptText = scriptText;
        ScriptApprovals.configuring(scriptText, GroovyLanguage.get(), true);
    }

    /**
     * Registers the script loaded from the configuration (also on {@code POST config.xml}) for approval.
     *
     * @return this object
     */
    protected Object readResolve() {
        ScriptApprovals.configuring(scriptText, GroovyLanguage.get(), false);
        return this;
    }

    public String getScriptText() {
        return scriptText;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends the script to the agent, runs it there and writes its output into the build log.
     *
     * @throws AbortException if the script is not approved or there is no connection to the agent
     * @throws IOException if the script cannot be sent to the agent
     * @throws InterruptedException if the thread is interrupted while waiting for the script
     */
    @Override
    public void runAction(TaskContext context) throws IOException, InterruptedException {
        ScriptApprovals.using(scriptText, GroovyLanguage.get());
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
