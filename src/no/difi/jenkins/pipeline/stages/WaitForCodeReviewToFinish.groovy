package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira

Git git
Jira jira

void script() {
    echo "Waiting for code review to finish..."
    jira.waitUntilCodeReviewIsFinished()
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
    node() {
        checkout scm
        git.deleteVerificationBranch()
    }
    jira.resumeWork()
}

