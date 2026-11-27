package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import hudson.tasks.Shell;
import io.jenkins.plugins.runtasksonagent.NodePropertyImpl;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GroovyScriptActionTest {

    @Test
    void outputOfTheScriptGoesToTheBuildLog(JenkinsRule jenkins) throws Exception {
        DumbSlave agent =
                agentWith(jenkins, post(new NoCondition(), new GroovyScriptAction("println 'GROOVY_' + (40 + 2)")));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        jenkins.assertLogContains("GROOVY_42", build);
    }

    @Test
    void scriptDoesNotRunOnTheBuiltInNode(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setNumExecutors(1);
        jenkins.jenkins
                .getNodeProperties()
                .replaceBy(List.of(new NodePropertyImpl(
                        List.of(post(new NoCondition(), new GroovyScriptAction("println 'GROOVY_' + (40 + 2)"))))));
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.setAssignedNode(jenkins.jenkins);
        project.getBuildersList().add(new Shell("echo hello"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        jenkins.assertLogNotContains("GROOVY_42", build);
        jenkins.assertLogContains("does not run on the built-in node", build);
    }
}
