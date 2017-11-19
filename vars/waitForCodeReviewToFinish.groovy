String call() {
    waitUntilIssueStatusIsNot env.ISSUE_STATUS_CODE_REVIEW
    env.codeApproved = "false"
    if (issueStatusIs(env.ISSUE_STATUS_CODE_APPROVED))
        env.codeApproved = "true"
}