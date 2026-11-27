package io.jenkins.plugins.runtasksonagent.action;

import hudson.AbortException;
import hudson.Extension;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import io.jenkins.plugins.runtasksonagent.util.ScriptApprovals;
import io.jenkins.plugins.runtasksonagent.util.ScriptRunner;
import java.io.IOException;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.SystemCommandLanguage;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Action with a parameter: a shell script to run on the agent. The script runs only once an administrator has
 * approved it (see {@link ScriptApprovals}).
 */
public class ShellScriptAction extends Action {

    private final String scriptText;

    /**
     * @param scriptText the text of the script, as typed into the form
     */
    @DataBoundConstructor
    public ShellScriptAction(String scriptText) {
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
     * Runs the script (see {@link ScriptRunner}).
     *
     * @param context the build and the agent to run the script on
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the thread is interrupted while waiting for the script
     * @throws AbortException if the script is not approved or exits with a non-zero code
     */
    @Override
    public void runAction(TaskContext context) throws IOException, InterruptedException {
        ScriptApprovals.using(scriptText, SystemCommandLanguage.get());
        int exitCode = ScriptRunner.run(context, scriptText);
        if (exitCode != 0) {
            throw new AbortException("Script exited with code " + exitCode);
        }
    }

    @Extension(ordinal = 100)
    public static final class DescriptorImpl extends TaskDescriptor<Action> {

        @Override
        public String getDisplayName() {
            return "Run custom shell script";
        }
    }
}
