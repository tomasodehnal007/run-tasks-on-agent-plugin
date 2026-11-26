package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
import static io.jenkins.plugins.runtasksonagent.TestSupport.post;
import static io.jenkins.plugins.runtasksonagent.TestSupport.projectOn;

import hudson.model.FreeStyleBuild;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.runtasksonagent.condition.NoCondition;
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
}
