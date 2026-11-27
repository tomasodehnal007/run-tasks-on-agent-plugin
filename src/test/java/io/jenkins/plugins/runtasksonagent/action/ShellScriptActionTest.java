package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.approveAll;
import static io.jenkins.plugins.runtasksonagent.TestSupport.countLines;
import static io.jenkins.plugins.runtasksonagent.TestSupport.enableSecurity;
import static io.jenkins.plugins.runtasksonagent.TestSupport.localContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the scripts use sh")
class ShellScriptActionTest {

    @TempDir
    Path workspace;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private TaskContext context;
    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        context = localContext(workspace, out);
    }

    @Test
    void outputOfTheScriptGoesToTheBuildLog() throws Exception {
        approved("echo HELLO").runAction(context);

        assertEquals(1, countLines(log(), "HELLO"), log());
    }

    @Test
    void scriptThatExitsWithANonZeroCodeFails() {
        ShellScriptAction action = approved("exit 3");

        AbortException e = assertThrows(AbortException.class, () -> action.runAction(context));

        assertEquals("Script exited with code 3", e.getMessage());
    }

    @Test
    void scriptThatIsNotApprovedDoesNotRun() {
        enableSecurity(jenkins);
        ShellScriptAction action = new ShellScriptAction("echo HELLO");

        AbortException e = assertThrows(AbortException.class, () -> action.runAction(context));

        assertTrue(e.getMessage().contains("not approved"), e.getMessage());
        assertEquals(0, countLines(log(), "HELLO"), log());
    }

    private static ShellScriptAction approved(String script) {
        ShellScriptAction action = new ShellScriptAction(script);
        approveAll();
        return action;
    }

    private String log() {
        context.listener().getLogger().flush();
        return out.toString(StandardCharsets.UTF_8);
    }
}
