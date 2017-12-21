import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import no.difi.jenkins.pipeline.Git

def call(String gitSshKey) {
    env.jobAborted = 'false'
    try {
        new Git().waitForAvailableVerificationSlot(gitSshKey)
    } catch (FlowInterruptedException ignored) {
        env.jobAborted = "true"
    }
}
