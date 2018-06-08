package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.ErrorHandler
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven

Jira jira
Git git
Maven maven
ErrorHandler errorHandler

void script(def params) {
    failIfJobIsAborted()
    jira.failIfCodeNotApproved()
    git.checkoutVerificationBranch()
    maven.deliver(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy, params.stagingEnvironment)
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
    maven.deletePublished params.stagingEnvironment, env.version
    jira.resumeWork()
}

private void failIfJobIsAborted() {
    if (env.jobAborted == 'true')
        errorHandler.trigger 'Job was aborted'
}
