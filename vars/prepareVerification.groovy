import java.time.ZoneId
import java.time.format.DateTimeFormatter
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Crucible
import static java.time.ZonedDateTime.now

def call(String gitSshKey, String crucibleUrl, String crucibleProjectKey, String crucibleUser, String cruciblePassword) {
    Git git = new Git()
    env.version = DateTimeFormatter.ofPattern('yyyy-MM-dd-HHmm').format(now(ZoneId.of('UTC'))) + "-" + git.readCommitId()
    String issueSummary = issueSummary()
    String issueId = issueId()
    String commitMessage = "${env.version}|${issueId}: ${issueSummary}".toString()
    String commitId = git.createVerificationBranch commitMessage, gitSshKey
    String repositoryName = git.repositoryName()
    Crucible crucible = new Crucible()
    crucible.synchronize crucibleUrl, repositoryName, crucibleUser, cruciblePassword
    crucible.createReview commitId, issueSummary, issueId, crucibleUrl, repositoryName, crucibleProjectKey, crucibleUser, cruciblePassword
}
