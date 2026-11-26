package io.jenkins.plugins.runtasksonagent;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.assertInOrder;
import static io.jenkins.plugins.runtasksonagent.TestSupport.countLines;
import static io.jenkins.plugins.runtasksonagent.TestSupport.echo;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.pre;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.action.ShellScriptAction;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
import io.jenkins.plugins.runtasksonagent.entry.PreBuildEntry;
import io.jenkins.plugins.runtasksonagent.execution.FreestyleRunListenerAccess;
import java.time.Duration;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineTest {

    @Test
    void eachAgentRunsItsTasksAroundItsBranchOfParallel(JenkinsRule jenkins) throws Exception {
        agentWith(jenkins, "agent-a", pre(new NoCondition(), echo("PRE_A")), post(new NoCondition(), echo("POST_A")));
        agentWith(jenkins, "agent-b", pre(new NoCondition(), echo("PRE_B")), post(new NoCondition(), echo("POST_B")));

        WorkflowRun build = run(jenkins, """
                parallel(
                    a: { node('agent-a') { echo 'A' } },
                    b: { node('agent-b') { echo 'B' } }
                )
                """, Result.SUCCESS);

        String log = JenkinsRule.getLog(build);
        assertInOrder(log, "PRE_A", "A", "POST_A");
        assertInOrder(log, "PRE_B", "B", "POST_B");
    }

    @Test
    void blocksRunningAtTheSameTimeOnOneAgentEachGetTheirTasks(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = agentWith(
                jenkins, "agent-a", pre(new NoCondition(), echo("PRE")), post(new NoCondition(), echo("POST")));
        agent.setNumExecutors(2);
        jenkins.jenkins.updateNode(agent);

        WorkflowRun build = run(jenkins, """
                parallel(
                    a: { node('agent-a') { sh 'sleep 3' } },
                    b: { node('agent-a') { sh 'sleep 3' } }
                )
                """, Result.SUCCESS);

        String log = JenkinsRule.getLog(build);
        assertEquals(2, countLines(log, "PRE"));
        assertEquals(2, countLines(log, "POST"));
    }

    @Test
    void declarativeRunsTheTasksAroundEveryStageWithItsOwnAgent(JenkinsRule jenkins) throws Exception {
        agentWith(jenkins, "agent-a", pre(new NoCondition(), echo("PRE_A")), post(new NoCondition(), echo("POST_A")));
        agentWith(jenkins, "agent-b", pre(new NoCondition(), echo("PRE_B")), post(new NoCondition(), echo("POST_B")));

        WorkflowRun build = run(jenkins, """
                pipeline {
                    agent none
                    stages {
                        stage('one') {
                            agent { label 'agent-a' }
                            steps { echo 'ONE' }
                        }
                        stage('two') {
                            agent { label 'agent-b' }
                            steps { echo 'TWO' }
                        }
                        stage('three') {
                            agent { label 'agent-a' }
                            steps { echo 'THREE' }
                        }
                    }
                }
                """, Result.SUCCESS);

        String log = JenkinsRule.getLog(build);
        assertInOrder(log, "PRE_A", "ONE", "POST_A");
        assertInOrder(log, "PRE_B", "TWO", "POST_B");
        assertInOrder(log, "TWO", "PRE_A", "THREE", "POST_A");
        assertEquals(2, countLines(log, "PRE_A"));
        assertEquals(1, countLines(log, "PRE_B"));
    }

    @Test
    void failingTaskBeforeTheNodeBlockAbortsThePipelineWhenSetTo(JenkinsRule jenkins) throws Exception {
        PreBuildEntry failing = pre(new NoCondition(), new ShellScriptAction("exit 5"));
        failing.setAbortBuildOnFailure(true);
        agentWith(jenkins, "agent-a", failing);

        // repeated, because an abort that arrived too early used to get lost only now and then
        for (int i = 0; i < 3; i++) {
            WorkflowRun build = run(jenkins, "node('agent-a') { echo 'SHOULD_NOT_RUN' }", Result.FAILURE);

            assertEquals(0, countLines(JenkinsRule.getLog(build), "SHOULD_NOT_RUN"));
        }
    }

    @Test
    void finishedPipelineStopsWaitingForSlowTasksAfterTheNodeBlockAfterTheTimeout(JenkinsRule jenkins)
            throws Exception {
        Duration original = FreestyleRunListenerAccess.nodeBlocksTimeout(Duration.ofSeconds(1));
        try {
            agentWith(jenkins, "agent-a", post(new NoCondition(), new ShellScriptAction("sleep 5")));

            WorkflowRun build = run(jenkins, "node('agent-a') { echo 'BODY' }", Result.SUCCESS);

            String log = JenkinsRule.getLog(build);
            assertTrue(log.contains("Waiting for the tasks after the node blocks to finish"), log);
            assertTrue(log.contains("Gave up waiting for the tasks after the node blocks"), log);
            jenkins.waitUntilNoActivity();
        } finally {
            FreestyleRunListenerAccess.nodeBlocksTimeout(original);
        }
    }

    private static WorkflowRun run(JenkinsRule jenkins, String script, Result expected) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return jenkins.assertBuildStatus(expected, job.scheduleBuild2(0));
    }
}
