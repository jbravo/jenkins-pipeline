package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven

Jira jira
Git git
Maven maven

void script(def params) {
    git.checkoutVerificationBranch()
    maven.deliver(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy, params.verificationEnvironment)
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
    maven.deletePublished params.verificationEnvironment, env.version
    jira.resumeWork()
}
