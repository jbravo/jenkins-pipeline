package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.ErrorHandler
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.DependencyTrack

ErrorHandler errorHandler
Git git
Jira jira
Docker dockerClient
Maven maven
DependencyTrack dependencyTrack

void script(def params) {
    if (git.isIntegrated(jira.issueId()))
        errorHandler.trigger "Code is already integrated"
    currentBuild.description = "Building from commit " + git.readCommitId()
    env.sourceCodeRepository = env.GIT_URL
    jira.setSourceCodeRepository env.sourceCodeRepository
    jira.startWork()
    if (maven.isMavenProject()) {
        if (params.enableDependencyTrack) {
            dtProjectId = dependencyTrack.getProjectID(env.JOB_NAME)
            maven.cycloneDX()
            dependencyTrackPublisher artifact: 'target/bom.xml', artifactType: 'bom', projectId: dtProjectId, failedTotalCritical: 0, failedTotalHigh: 2, failedTotalLow: 20, failedTotalMedium: 10, synchronous: true
            if (currentBuild.result == "FAILURE") {
                env.errorMessage = "DependencyTrack check failed, Vulnerability thresholds exceed"
            }
        }
        maven.verify params.MAVEN_OPTS
        dockerClient.verify()
    }
    else
        dockerClient.verify()
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
