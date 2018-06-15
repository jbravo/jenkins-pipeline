package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira

Git git
Jira jira

void script(def params) {
    echo "Waiting for available verification slot..."
    git.waitForAvailableVerificationSlot()
}

void failureScript() {
    cleanup()
    jira.addFailureComment()
}

void abortedScript() {
    cleanup()
    jira.addAbortedComment()
}

private void cleanup() {
    jira.resumeWork()
}

