String call() {
    Jira jira = new Jira()
    jira.waitUntilIssueStatusIsNot env.ISSUE_STATUS_CODE_REVIEW
    env.codeApproved = "false"
    if (jira.issueStatusIs(env.ISSUE_STATUS_CODE_APPROVED))
        env.codeApproved = "true"
}