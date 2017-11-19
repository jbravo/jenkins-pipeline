def call(def transitionId) {
    jiraTransitionIssue idOrKey: issueId(), input: [transition: [id: transitionId]]
}

def call(def sourceStatus, def transitionId) {
    if (issueStatusIs(sourceStatus)) {
        echo "Transitioning issue from ${sourceStatus} with transition ${transitionId}"
        transitionIssue transitionId
    }
}
