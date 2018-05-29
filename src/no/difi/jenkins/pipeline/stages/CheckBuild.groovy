package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.ErrorHandler
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven

ErrorHandler errorHandler
Git git
Jira jira
Docker dockerClient
Maven maven

void script(def params) {
    if (git.isIntegrated(jira.issueId(), params.gitSshKey))
        errorHandler.trigger "Code is already integrated"
    currentBuild.description = "Building from commit " + git.readCommitId()
    env.sourceCodeRepository = env.GIT_URL
    jira.setSourceCodeRepository env.sourceCodeRepository
    jira.startWork()
    if (maven.isMavenProject())
        maven.verify params.MAVEN_OPTS
    else
        dockerClient.verify()
}
