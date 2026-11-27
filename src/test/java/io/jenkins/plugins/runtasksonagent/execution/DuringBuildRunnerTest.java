package io.jenkins.plugins.runtasksonagent.execution;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.echo;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.TaskContext;
import io.jenkins.plugins.runtasksonagent.TaskDescriptor;
import io.jenkins.plugins.runtasksonagent.condition.Condition;
import io.jenkins.plugins.runtasksonagent.entry.DuringBuildEntry;
import io.jenkins.plugins.runtasksonagent.util.IntervalUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DuringBuildRunnerTest {

    @Test
    void checksDoNotRunOnTheSharedTimerOfJenkins(JenkinsRule jenkins) throws Exception {
        ThreadRecordingCondition.THREADS.clear();
        DumbSlave agent = agentWith(
                jenkins,
                new DuringBuildEntry(1, IntervalUnit.SECONDS, true, new ThreadRecordingCondition(), echo("TICK")));

        jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "sleep 3"));

        List<String> threads = ThreadRecordingCondition.THREADS;
        assertFalse(threads.isEmpty(), "the condition was never checked");
        assertTrue(
                threads.stream().allMatch(name -> name.startsWith("RunTasksOnAgent.DuringBuild")), threads::toString);
    }

    /** Records the name of the thread checking it; never holds. */
    public static class ThreadRecordingCondition extends Condition {

        static final List<String> THREADS = new CopyOnWriteArrayList<>();

        @Override
        public boolean conditionPasses(TaskContext context) {
            THREADS.add(Thread.currentThread().getName());
            return false;
        }

        @TestExtension
        public static final class DescriptorImpl extends TaskDescriptor<Condition> {}
    }
}
