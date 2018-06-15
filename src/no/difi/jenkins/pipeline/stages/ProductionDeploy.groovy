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
    if (params.productionEnvironmentType == 'puppet') {
        puppet.deploy params.productionEnvironment, env.version, params.puppetModules, params.librarianModules, params.puppetApplyList
    } else if (params.productionEnvironmentType == 'docker') {
        dockerClient.deployStack params.productionEnvironment, params.stackName, env.version
    }
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
    dockerClient.deletePublished params.productionEnvironment, env.version
    if (maven.isMavenProject())
        maven.deletePublished params.productionEnvironment, env.version
    git.deleteWorkBranch()
}