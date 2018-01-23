package no.difi.jenkins.pipeline

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

Map config
ErrorHandler errorHandler

String issueId() {
    env.BRANCH_NAME.tokenize('/')[-1]
}

String issueStatus() {
    try {
        jiraGetIssue(idOrKey: issueId()).data.fields['status']['id']
    } catch (e) {
        errorHandler.trigger "Failed to get issue status: ${e.message}"
    }
}

boolean issueStatusIs(def targetStatus) {
    issueStatus() == targetStatus as String
}

String issueSummary() {
    try {
        jiraGetIssue(idOrKey: issueId()).data.fields['summary']
    } catch (e) {
        errorHandler.trigger "Failed to get issue summary: ${e.message}"
    }
}

void readyForCodeReview() {
    changeIssueStatus config.transitions.readyForCodeReview
}

void startVerification() {
    changeIssueStatus '291'
}

void startWork() {
    changeIssueStatus config.statuses.open, config.transitions.start
    changeIssueStatus '10717', '401' // env.ISSUE_STATUS_READY_FOR_VERIFICATION env.ISSUE_TRANSITION_CANCEL_VERIFICATION
    if (!issueStatusIs(config.statuses.inProgress))
        errorHandler.trigger "Failed to change issue status to 'in progress'"
}

void resumeWork() {
    changeIssueStatus '10717', '401' // env.ISSUE_STATUS_READY_FOR_VERIFICATION env.ISSUE_TRANSITION_CANCEL_VERIFICATION
    changeIssueStatus config.statuses.codeReview, config.transitions.resumeWork
    if (!issueStatusIs(config.statuses.inProgress))
        errorHandler.trigger "Failed to change issue status to 'in progress'"
}

void waitUntilVerificationIsStarted() {
    waitUntilIssueStatusIs config.statuses.codeReview
}

void waitUntilCodeReviewIsFinished() {
    waitUntilIssueStatusIsNot config.statuses.codeReview
}

boolean isCodeApproved() {
    issueStatusIs config.statuses.codeApproved
}

void waitUntilManualVerificationIsStarted() {
    waitUntilIssueStatusIs config.statuses.manualVerification
}

void waitUntilManualVerificationIsFinished() {
    waitUntilIssueStatusIsNot config.statuses.manualVerification
}

void assertManualVerificationWasSuccessful() {
    ensureIssueStatusIs config.statuses.manualVerificationOk
}

private void changeIssueStatus(def transitionId) {
    try {
        jiraTransitionIssue idOrKey: issueId(), input: [transition: [id: transitionId]]
    } catch (e) {
        errorHandler.trigger "Failed to move issue to '${transitionName transitionId}': ${e.message}"
    }
}

private String transitionName(def transitionId) {
    switch (transitionId) {
        case config.statuses.inProgress: return 'in progress'
        case config.statuses.readyForCodeReview: return 'ready for code review'
        default: return "<transition ${transitionId}>"
    }
}

private void changeIssueStatus(def sourceStatus, def transitionId) {
    if (issueStatusIs(sourceStatus)) {
        echo "Transitioning issue from ${sourceStatus} with transition ${transitionId}"
        changeIssueStatus transitionId
    }
}

private void waitUntilIssueStatusIs(def targetStatus) {
    env.jobAborted = 'false'
    try {
        retry(count: 1000000) {
            if (!issueStatusIs(targetStatus)) {
                sleep 10
                errorHandler.trigger "Waiting until issue status is ${targetStatus}..."
            }
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}

private void waitUntilIssueStatusIsNot(def targetStatus) {
    env.jobAborted = 'false'
    try {
        retry(count: 1000000) {
            if (issueStatusIs(targetStatus)) {
                sleep 10
                errorHandler.trigger "Waiting until issue status is not ${targetStatus}..."
            }
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}

private void ensureIssueStatusIs(def issueStatus) {
    if (!issueStatusIs(issueStatus))
        errorHandler.trigger "Issue status is not ${issueStatus}"
}


void addComment(String comment) {
    try {
        jiraAddComment(
                idOrKey: issueId(),
                comment: comment,
                auditLog: false
        )
    } catch (e) {
        errorHandler.trigger "Failed to add comment to issue: ${e.message}"
    }
}
