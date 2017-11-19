import java.time.ZoneId
import java.time.format.DateTimeFormatter
import no.difi.jenkins.pipeline.Git
import static java.time.ZonedDateTime.now

def call(String gitSshKey, String crucibleUrl, String crucibleProjectKey, String crucibleUser, String cruciblePassword) {
    env.version = DateTimeFormatter.ofPattern('yyyy-MM-dd-HHmm').format(now(ZoneId.of('UTC'))) + "-" + new Git().readCommitId()
    String issueSummary = issueSummary()
    String issueId = issueId()
    String commitMessage = "${env.version}|${issueId}: ${issueSummary}".toString()
    String commitId = createVerificationRevision commitMessage, gitSshKey
    String repositoryUrl = 'git remote get-url origin'.execute([], new File(pwd())).text
    String repositoryName = repositoryUrl.tokenize(':/')[-1].tokenize('.')[0].trim()
    synchronizeCrucible crucibleUrl, repositoryName, crucibleUser, cruciblePassword
    createReview commitId, issueSummary, issueId, crucibleUrl, repositoryName, crucibleProjectKey, crucibleUser, cruciblePassword
}
