import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import no.difi.jenkins.pipeline.Git

def call(String gitSshKey) {
    env.jobAborted = 'false'
    try {
        while (true) {
            String verificationBranch = new Git().currentVerificationBranch(gitSshKey)
            if (verificationBranch != null) {
                echo "Branch ${verificationBranch} is using the verification slot. Waiting 10 seconds..."
                sleep 10
            } else return
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}
