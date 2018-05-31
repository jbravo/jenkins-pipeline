package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.ErrorHandler
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

Git git
Jira jira
ErrorHandler errorHandler

void script(def params) {
    failIfJobIsAborted()
    echo "Waiting for available verification slot..."
    env.jobAborted = 'false'
    try {
        git.waitForAvailableVerificationSlot(params.gitSshKey)
    } catch (FlowInterruptedException ignored) {
        env.jobAborted = "true"
    }
    failIfJobIsAborted()
}

void failureScript() {
    jira.resumeWork()
}

void abortedScript() {
    failureScript()
}

private void failIfJobIsAborted() {
    if (env.jobAborted == 'true')
        errorHandler.trigger 'Job was aborted'
}
