package io.jenkins.plugins.runtasksonagent.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.AbortException;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.Language;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.UnapprovedUsageException;

/**
 * Puts the scripts configured on an agent through the Script Security plugin's "In-process Script Approval".
 *
 * <p>Tasks are part of the agent's configuration, which a user with only Agent/Configure may change, but they
 * run code inside every build on the agent (Groovy even inside the agent's JVM, which Jenkins otherwise allows
 * only to administrators). So every script must be approved by an administrator before it runs.
 *
 * <p>The objects holding a script call {@link #configuring} when they are created from the form (constructor)
 * and when they are loaded from XML ({@code readResolve}, which also covers {@code POST config.xml}), and
 * {@link #using} right before the script runs.
 */
public final class ScriptApprovals {

    private ScriptApprovals() {}

    /**
     * Records that the script was configured by the current user. A script not approved yet is added to the list
     * of pending approvals.
     *
     * @param script the script; {@code null} is ignored
     * @param language the language of the script
     * @param fromForm {@code true} when called from the form, where an administrator's script may be approved at
     *     once if Script Security is set to do that; {@code false} when loading XML, which must never approve
     */
    public static void configuring(@CheckForNull String script, Language language, boolean fromForm) {
        if (script == null) {
            return;
        }
        ScriptApproval.get()
                .configuring(script, language, ApprovalContext.create().withCurrentUser(), fromForm);
    }

    /**
     * Checks that the script is approved.
     *
     * @param script the script about to run; {@code null} counts as an empty script
     * @param language the language of the script
     * @throws AbortException if the script is not approved; the message says where to approve it
     */
    public static void using(@CheckForNull String script, Language language) throws AbortException {
        try {
            ScriptApproval.get().using(script == null ? "" : script, language);
        } catch (UnapprovedUsageException e) {
            throw new AbortException("The script is not approved yet; an administrator must approve it in"
                    + " Manage Jenkins > In-process Script Approval");
        }
    }
}
