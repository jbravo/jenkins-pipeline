package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.Puppet

Git git
Jira jira
Puppet puppet
Docker dockerClient
Maven maven

void script(def params) {
    git.checkoutVerificationBranch()
    jira.updateIssuesForManualVerification env.version, env.sourceCodeRepository
    if (params.stagingEnvironmentType == 'puppet') {
        puppet.deploy params.stagingEnvironment, env.version, params.puppetModules, params.librarianModules, params.puppetApplyList
    } else if (params.stagingEnvironmentType == 'puppet2') {
            puppet.deploy2 params.stagingEnvironment, env.version, params.puppetModules, params.puppetApplyList
    } else if (params.stagingEnvironmentType == 'docker') {
        dockerClient.deployStack params.stagingEnvironment, params.stackName, env.version
    }
    jira.startManualVerification()
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
    jira.stagingFailed()
    dockerClient.deletePublished params.stagingEnvironment, env.version
    if (maven.isMavenProject())
        maven.deletePublished params.stagingEnvironment, env.version
    git.deleteWorkBranch()
}