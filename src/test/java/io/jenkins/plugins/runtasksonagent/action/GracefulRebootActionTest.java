package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.countLines;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.NodePropertyImpl;
import io.jenkins.plugins.runtasksonagent.condition.ScriptCondition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The agent has two executors, so two builds run on it at the same time; a build lasts until the test creates the
 * file {@code release}. Only builds that create the file {@code trigger} in their workspace reboot the agent. The
 * reboot command is replaced by a harmless one, so the agent does not come back and the action gives up waiting
 * for it after a short time.
 */
@WithJenkins
class GracefulRebootActionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreTimeout() {
        RebootAction.reconnectTimeout = Duration.ofMinutes(10);
    }

    @Test
    void waitsUntilTheOtherBuildsOnTheAgentHaveFinished(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = rebootingAgent(jenkins);
        FreeStyleProject longJob = projectOn(jenkins, agent, waitForRelease());
        FreeStyleProject rebootingJob = projectOn(jenkins, agent, "touch trigger");

        QueueTaskFuture<FreeStyleBuild> longBuild = longJob.scheduleBuild2(0);
        longBuild.waitForStart();
        QueueTaskFuture<FreeStyleBuild> rebootingBuild = rebootingJob.scheduleBuild2(0);
        FreeStyleBuild rebooting = rebootingBuild.waitForStart();
        jenkins.waitForMessage("Waiting for the other builds", rebooting);
        Thread.sleep(2_000); // long enough for the command to have run if the action did not wait

        assertTrue(rebooting.isInProgress());
        assertEquals(0, countLines(JenkinsRule.getLog(rebooting), "REBOOT_COMMAND"));

        Files.createFile(tempDir.resolve("release"));
        jenkins.assertBuildStatusSuccess(longBuild);
        jenkins.assertBuildStatusSuccess(rebootingBuild);
        assertEquals(1, countLines(JenkinsRule.getLog(rebooting), "REBOOT_COMMAND"));
    }

    @Test
    void onlyOneOfTwoBuildsThatBothWantAGracefulRebootRebootsTheAgent(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = rebootingAgent(jenkins);
        FreeStyleProject first = projectOn(jenkins, agent, "touch trigger");
        FreeStyleProject second = projectOn(jenkins, agent, "touch trigger; " + waitForRelease());

        QueueTaskFuture<FreeStyleBuild> secondBuild = second.scheduleBuild2(0);
        secondBuild.waitForStart();
        QueueTaskFuture<FreeStyleBuild> firstBuild = first.scheduleBuild2(0);
        jenkins.waitForMessage("Waiting for the other builds", firstBuild.waitForStart());

        Files.createFile(tempDir.resolve("release"));
        String firstLog = JenkinsRule.getLog(jenkins.assertBuildStatusSuccess(firstBuild));
        String secondLog = JenkinsRule.getLog(jenkins.assertBuildStatusSuccess(secondBuild));
        assertEquals(1, countLines(firstLog, "REBOOT_COMMAND"));
        assertEquals(0, countLines(secondLog, "REBOOT_COMMAND"));
        assertTrue(secondLog.contains("Another build is already rebooting the agent"));
    }

    private DumbSlave rebootingAgent(JenkinsRule jenkins) throws Exception {
        RebootAction.reconnectTimeout = Duration.ofSeconds(1);
        DumbSlave agent = new DumbSlave(
                "two-executors",
                Files.createDirectories(tempDir.resolve("agent")).toString(),
                jenkins.createComputerLauncher(null));
        agent.setNumExecutors(2);
        agent.setNodeProperties(List.of(new NodePropertyImpl(List.of(
                post(new ScriptCondition("test -f trigger"), new GracefulRebootAction("echo REBOOT_COMMAND"))))));
        jenkins.jenkins.addNode(agent);
        jenkins.waitOnline(agent);
        return agent;
    }

    private String waitForRelease() {
        return "while [ ! -f '" + tempDir.resolve("release") + "' ]; do sleep 0.2; done";
    }
}
