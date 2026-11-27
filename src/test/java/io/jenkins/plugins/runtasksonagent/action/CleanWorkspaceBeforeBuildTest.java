package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.pre;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import hudson.tasks.Shell;
import io.jenkins.plugins.runtasksonagent.NodePropertyImpl;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the build steps use sh")
class CleanWorkspaceBeforeBuildTest {

    @Test
    void cleansTheWorkspaceLeftByThePreviousBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = projectOn(agentWithTwoExecutors(jenkins), jenkins);
        project.getBuildersList().add(new Shell("test ! -f leftover; touch leftover"));

        jenkins.buildAndAssertSuccess(project);
        // fails if the leftover of the first build was not cleaned
        jenkins.buildAndAssertSuccess(project);
    }

    /** Concurrent builds of one job: the second must not clean the workspace the first is still using. */
    @Test
    void doesNotCleanTheWorkspaceOfARunningBuild(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = agentWithTwoExecutors(jenkins);
        FreeStyleProject project = projectOn(agent, jenkins);
        project.setConcurrentBuild(true);
        // The first build waits for the second one to start, then checks its file is still there.
        project.getBuildersList()
                .add(new Shell("if [ \"$BUILD_NUMBER\" = 1 ]; then touch mine; while [ ! -f ../second-started ]; do"
                        + " sleep 0.2; done; test -f mine; fi; touch ../second-started"));

        var first = project.scheduleBuild2(0);
        FreeStyleBuild firstBuild = first.waitForStart();
        while (!agent.getWorkspaceFor(project).child("mine").exists()) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        FreeStyleBuild second = jenkins.buildAndAssertSuccess(project);

        jenkins.assertBuildStatusSuccess(first);
        jenkins.assertLogContains("is in use by another build, it is not cleaned", second);
        assertTrue(firstBuild
                .getWorkspace()
                .getRemote()
                .equals(agent.getWorkspaceFor(project).getRemote()));
    }

    private static DumbSlave agentWithTwoExecutors(JenkinsRule jenkins) throws Exception {
        DumbSlave agent =
                new DumbSlave("concurrent", jenkins.createTmpDir().getPath(), jenkins.createComputerLauncher(null));
        agent.setNumExecutors(2);
        agent.setNodeProperties(
                List.of(new NodePropertyImpl(List.of(pre(new NoCondition(), new CleanWorkspaceAction())))));
        jenkins.jenkins.addNode(agent);
        jenkins.waitOnline(agent);
        return agent;
    }

    private static FreeStyleProject projectOn(DumbSlave agent, JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.setAssignedNode(agent);
        return project;
    }
}
