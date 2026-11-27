package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.countLines;
import static io.jenkins.plugins.runtasksonagent.TestSupport.enableSecurity;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.NodePropertyImpl;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** The reboot command is always replaced by a harmless one, so the agent does not come back. */
@WithJenkins
class RebootActionTest {

    @AfterEach
    void restoreTimeout() {
        RebootAction.reconnectTimeout = Duration.ofMinutes(10);
    }

    @Test
    void takesTheAgentOfflineAndRunsTheRebootCommand(JenkinsRule jenkins) throws Exception {
        RebootAction.reconnectTimeout = Duration.ofSeconds(1);
        DumbSlave agent = agentWith(jenkins, post(new NoCondition(), new RebootAction("echo REBOOT_COMMAND")));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        assertEquals(1, countLines(JenkinsRule.getLog(build), "REBOOT_COMMAND"));
        assertTrue(agent.toComputer().isOffline());
    }

    @Test
    void customCommandThatIsNotApprovedDoesNotRunAndTheAgentStaysOnline(JenkinsRule jenkins) throws Exception {
        enableSecurity(jenkins);
        DumbSlave agent = jenkins.createOnlineSlave();
        agent.setNodeProperties(List.of(
                new NodePropertyImpl(List.of(post(new NoCondition(), new RebootAction("echo REBOOT_COMMAND"))))));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        assertEquals(0, countLines(JenkinsRule.getLog(build), "REBOOT_COMMAND"));
        jenkins.assertLogContains("not approved", build);
        assertFalse(agent.toComputer().isOffline());
    }
}
