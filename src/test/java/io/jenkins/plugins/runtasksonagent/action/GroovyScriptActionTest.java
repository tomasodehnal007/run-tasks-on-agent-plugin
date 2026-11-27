package io.jenkins.plugins.runtasksonagent.action;

import static io.jenkins.plugins.runtasksonagent.TestSupport.agentWith;
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
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GroovyScriptActionTest {

    private static final String SCRIPT = "println 'GROOVY_' + (40 + 2)";

    @Test
    void outputOfTheScriptGoesToTheBuildLog(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = agentWith(jenkins, post(new NoCondition(), new GroovyScriptAction(SCRIPT)));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        jenkins.assertLogContains("GROOVY_42", build);
    }

    @Test
    void scriptThatIsNotApprovedDoesNotRun(JenkinsRule jenkins) throws Exception {
        enableSecurity(jenkins);
        DumbSlave agent = jenkins.createOnlineSlave();
        agent.setNodeProperties(
                List.of(new NodePropertyImpl(List.of(post(new NoCondition(), new GroovyScriptAction(SCRIPT))))));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(projectOn(jenkins, agent, "echo hello"));

        jenkins.assertLogNotContains("GROOVY_42", build);
        jenkins.assertLogContains("not approved", build);
    }

    /** A user who may only configure agents uploads the script as XML: it must wait for approval. */
    @Test
    void scriptUploadedAsXmlByAnAgentConfigurerWaitsForApproval(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, hudson.model.Computer.CONFIGURE, hudson.model.Computer.EXTENDED_READ)
                .everywhere()
                .to("configurer"));
        String xml = Jenkins.XSTREAM2.toXML(agent);
        xml = xml.replace(
                "<nodeProperties/>",
                "<nodeProperties><io.jenkins.plugins.runtasksonagent.NodePropertyImpl><entries>"
                        + "<io.jenkins.plugins.runtasksonagent.entry.PostBuildEntry>"
                        + "<condition class=\"io.jenkins.plugins.runtasksonagent.condition.NoCondition\"/>"
                        + "<action class=\"io.jenkins.plugins.runtasksonagent.action.GroovyScriptAction\">"
                        + "<scriptText>" + SCRIPT.replace("'", "&apos;") + "</scriptText></action>"
                        + "</io.jenkins.plugins.runtasksonagent.entry.PostBuildEntry>"
                        + "</entries></io.jenkins.plugins.runtasksonagent.NodePropertyImpl></nodeProperties>");
        assertTrue(xml.contains("GroovyScriptAction"), xml);

        try (JenkinsRule.WebClient client = jenkins.createWebClient().login("configurer")) {
            WebRequest request = new WebRequest(
                    client.createCrumbedUrl(agent.toComputer().getUrl() + "config.xml"), HttpMethod.POST);
            request.setAdditionalHeader("Content-Type", "application/xml");
            request.setRequestBody(xml);
            client.getPage(request);
        }

        assertEquals(
                1,
                jenkins.jenkins
                        .getNode(agent.getNodeName())
                        .getNodeProperties()
                        .get(NodePropertyImpl.class)
                        .getEntries()
                        .size());
        assertFalse(ScriptApproval.get().isScriptApproved(SCRIPT, GroovyLanguage.get()));
        assertTrue(
                ScriptApproval.get().getPendingScripts().stream().anyMatch(pending -> pending.script.equals(SCRIPT)));
    }
}
