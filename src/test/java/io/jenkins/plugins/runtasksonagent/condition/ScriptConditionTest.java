package io.jenkins.plugins.runtasksonagent.condition;

import static io.jenkins.plugins.runtasksonagent.TestSupport.approveAll;
import static io.jenkins.plugins.runtasksonagent.TestSupport.enableSecurity;
import static io.jenkins.plugins.runtasksonagent.TestSupport.localContext;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import java.io.ByteArrayOutputStream;
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
class ScriptConditionTest {

    @TempDir
    Path workspace;

    private TaskContext context;
    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        context = localContext(workspace, new ByteArrayOutputStream());
    }

    @Test
    void holdsWhenTheScriptExitsWithZero() throws Exception {
        assertTrue(approved("exit 0").conditionPasses(context));
    }

    @Test
    void doesNotHoldWhenTheScriptExitsWithANonZeroCode() throws Exception {
        assertFalse(approved("exit 1").conditionPasses(context));
    }

    @Test
    void scriptThatIsNotApprovedIsNotRun() {
        enableSecurity(jenkins);
        ScriptCondition condition = new ScriptCondition("touch ran");

        assertThrows(AbortException.class, () -> condition.conditionPasses(context));
        assertFalse(workspace.resolve("ran").toFile().exists());
    }

    private static ScriptCondition approved(String script) {
        ScriptCondition condition = new ScriptCondition(script);
        approveAll();
        return condition;
    }
}
