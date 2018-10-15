package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira

Git git
Jira jira

void script() {
    echo "Waiting for approval..."
    if (!jira.waitUntilManualVerificationIsFinishedAndAssertSuccess(env.sourceCodeRepository)) return
    String fixVersions = jira.fixVersions()
    if (fixVersions && !fixVersions.contains(env.version)) {
        env.verification = 'false'
        node() {
            checkout scm
            git.deleteWorkBranch()
        }
    }
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
        git.deleteWorkBranch()
    }
    jira.stagingFailed()
}

