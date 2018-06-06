package no.difi.jenkins.pipeline.stages

import no.difi.jenkins.pipeline.Crucible
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Jira

import java.time.ZoneId
import java.time.format.DateTimeFormatter

import static java.time.ZonedDateTime.now

Git git
Jira jira
Crucible crucible

void script(def params) {
    env.version = DateTimeFormatter.ofPattern('yyyy-MM-dd-HHmm').format(now(ZoneId.of('UTC'))) + "-" + git.readCommitId()
    String issueSummary = jira.issueSummary().trim()
    String issueId = jira.issueId()
    String commitMessage = "${env.version}|${issueId}: ${issueSummary}".toString()
    echo "Generated commit message <${commitMessage}>"
    String commitId = git.createVerificationBranch commitMessage, params.gitSshKey
    currentBuild.description = "{\"version\":\"${env.version}\",\"commit\":\"${commitId.take(7)}\",\"issue\":\"${issueId}\"}"
    git.resetVerificationBranchToOrigin() // In case there was an existing local branch from previous build
    String repositoryName = git.repositoryName()
    crucible.synchronize env.CRUCIBLE_URL, repositoryName, env.crucible_USR, env.crucible_PSW
    crucible.createReview commitId, issueSummary, issueId, env.CRUCIBLE_URL, repositoryName, env.CRUCIBLE_PROJECT_KEY, env.crucible_USR, env.crucible_PSW
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
    jira.resumeWork()
}
