package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven

Jira jira
Git git
Docker dockerClient
Maven maven

void script(def params) {
    git.checkoutVerificationBranch()
    dockerClient.buildAndPublish params.verificationEnvironment, env.version
}

void failureScript(def params) {
    cleanup(params)
    jira.addFailureComment()
}

void abortedScript(def params) {
    cleanup(params)
    jira.addAbortedComment()
}

private void cleanup(def params) {
    git.deleteVerificationBranch(params.gitSshKey)
    dockerClient.deletePublished params.verificationEnvironment, env.version
    if (maven.isMavenProject())
        maven.deletePublished params.verificationEnvironment, env.version
    jira.resumeWork()
}
