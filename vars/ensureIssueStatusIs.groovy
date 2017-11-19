def call(def issueStatus) {
    if (!issueStatusIs(issueStatus))
        error "Issue status is not ${issueStatus}"
}
