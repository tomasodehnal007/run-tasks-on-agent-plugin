package io.jenkins.plugins.runtasksonagent.condition;

import hudson.Extension;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import io.jenkins.plugins.runtasksonagent.util.ScriptApprovals;
import io.jenkins.plugins.runtasksonagent.util.ScriptRunner;
import java.io.IOException;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.SystemCommandLanguage;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Condition decided by a shell script run on the agent: exit code 0 means the condition holds. The script runs
 * only once an administrator has approved it (see {@link ScriptApprovals}).
 */
public class ScriptCondition extends Condition {

    private final String scriptText;

    /**
     * @param scriptText the text of the script, as typed into the form
     */
    @DataBoundConstructor
    public ScriptCondition(String scriptText) {
        this.scriptText = scriptText;
        ScriptApprovals.configuring(scriptText, SystemCommandLanguage.get(), true);
    }

    /**
     * Registers the script loaded from the configuration (also on {@code POST config.xml}) for approval.
     *
     * @return this object
     */
    protected Object readResolve() {
        ScriptApprovals.configuring(scriptText, SystemCommandLanguage.get(), false);
        return this;
    }

    public String getScriptText() {
        return scriptText;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The script runs on the agent (see {@link ScriptRunner}) and its output goes into the build log.
     *
     * @return {@code true} if the script exits with code 0
     * @throws hudson.AbortException if the script is not approved
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the thread is interrupted while waiting for the script
     */
    @Override
    public boolean conditionPasses(TaskContext context) throws IOException, InterruptedException {
        ScriptApprovals.using(scriptText, SystemCommandLanguage.get());
        return ScriptRunner.run(context, scriptText) == 0;
    }

    @Extension(ordinal = 70)
    public static final class DescriptorImpl extends TaskDescriptor<Condition> {

        @Override
        public String getDisplayName() {
            return "Script exits with code 0";
        }
    }
}
