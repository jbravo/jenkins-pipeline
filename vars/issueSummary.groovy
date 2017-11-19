String call() {
    return jiraGetIssue(idOrKey: issueId()).data.fields['summary']
}
