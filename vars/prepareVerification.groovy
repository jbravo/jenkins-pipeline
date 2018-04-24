import java.time.ZoneId
import java.time.format.DateTimeFormatter
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Crucible
import static java.time.ZonedDateTime.now

def call(String gitSshKey, String crucibleUrl, String crucibleProjectKey, String crucibleUser, String cruciblePassword) {
    Git git = new Git()
    Jira jira = new Jira()
    env.version = DateTimeFormatter.ofPattern('yyyy-MM-dd-HHmm').format(now(ZoneId.of('UTC'))) + "-" + git.readCommitId()
    String issueSummary = jira.issueSummary().trim()
    String issueId = jira.issueId()
    String commitMessage = "${env.version}|${issueId}: ${issueSummary}".toString()
    echo "Generated commit message <${commitMessage}>"
    String commitId = git.createVerificationBranch commitMessage, gitSshKey
    currentBuild.description = "{\"version\":\"${env.version}\",\"commit\":\"${commitId.take(7)}\",\"issue\":\"${issueId}\"}"
    git.resetVerificationBranchToOrigin() // In case there was an existing local branch from previous build
    String repositoryName = git.repositoryName()
    Crucible crucible = new Crucible()
    crucible.synchronize crucibleUrl, repositoryName, crucibleUser, cruciblePassword
    crucible.createReview commitId, issueSummary, issueId, crucibleUrl, repositoryName, crucibleProjectKey, crucibleUser, cruciblePassword
}
