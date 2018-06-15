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
    jira.failIfCodeNotApproved()
    git.checkoutVerificationBranch()
    dockerClient.buildAndPublish params.stagingEnvironment, env.version
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
    git.deleteVerificationBranch()
    dockerClient.deletePublished params.stagingEnvironment, env.version
    if (maven.isMavenProject())
        maven.deletePublished params.stagingEnvironment, env.version
    jira.resumeWork()
}

