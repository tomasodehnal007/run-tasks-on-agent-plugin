package io.jenkins.plugins.runtasksonagent.util;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.localContext;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.model.FreeStyleBuild;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.action.GroovyScriptAction;
import io.jenkins.plugins.runtasksonagent.action.ShellScriptAction;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ScriptTimeoutTest {

    @TempDir
    Path workspace;

    @AfterEach
    void restoreTimeout() {
        ScriptRunner.timeout = Duration.ofMinutes(60);
        Thread.interrupted();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the script uses sh")
    void shellScriptThatRunsTooLongIsKilled(JenkinsRule jenkins) {
        ScriptRunner.timeout = Duration.ofMillis(500);
        TaskContext context = localContext(workspace, new ByteArrayOutputStream());
        long start = System.nanoTime();

        AbortException e = assertThrows(
                AbortException.class, () -> new ShellScriptAction("sleep 60; touch late").runAction(context));

        assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 30, "the script was not stopped");
        assertFalse(Thread.currentThread().isInterrupted(), "the interrupt of the timeout leaked out");
        assertFalse(workspace.resolve("late").toFile().exists());
    }

    @Test
    void codeThatFinishesInTimeIsNotInterrupted(JenkinsRule jenkins) throws Exception {
        ScriptRunner.timeout = Duration.ofMillis(200);

        assertEquals("done", ScriptRunner.withTimeout("The code", () -> "done"));
        Thread.sleep(500); // after the time limit: the cancelled watchdog must not interrupt the thread now

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptForAnotherReasonIsNotReportedAsTimeout(JenkinsRule jenkins) {
        assertThrows(
                InterruptedException.class,
                () -> ScriptRunner.withTimeout("The code", () -> {
                    throw new InterruptedException("aborted");
                }));
    }

    @Test
    void groovyScriptThatRunsTooLongIsStopped(JenkinsRule jenkins) throws Exception {
        ScriptRunner.timeout = Duration.ofSeconds(2);
        DumbSlave agent = agentWith(
                jenkins,
                post(new NoCondition(), new GroovyScriptAction("Thread.sleep(120000); println 'GROOVY_LATE'")));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        jenkins.assertLogContains("The Groovy script did not finish within", build);
        jenkins.assertLogNotContains("GROOVY_LATE", build);
    }
}
