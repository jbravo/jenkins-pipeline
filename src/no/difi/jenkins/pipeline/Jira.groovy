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
    changeIssueStatus config.transitions.startVerification
}

void startWork() {
    changeIssueStatus config.statuses.open, config.transitions.start
    changeIssueStatus config.statuses.readyForVerification, config.transitions.cancelVerification
    changeIssueStatus config.statuses.codeReview, config.transitions.resumeWork
    if (!issueStatusIs(config.statuses.inProgress))
        errorHandler.trigger "Failed to change issue status to 'in progress'"
}

void startManualVerification() {
    changeIssueStatus config.transitions.startManualVerification
}

void approveManualVerification() {
    changeIssueStatus config.transitions.approveManualVerification
}

void resumeWork() {
    changeIssueStatus config.statuses.readyForVerification, config.transitions.cancelVerification
    changeIssueStatus config.statuses.codeReview, config.transitions.resumeWork
    changeIssueStatus config.statuses.codeApproved, config.transitions.resumeWorkFromApprovedCode
    if (!issueStatusIs(config.statuses.inProgress))
        errorHandler.trigger "Failed to change issue status to 'in progress'"
}

void close() {
    changeIssueStatus config.statuses.codeApproved, config.transitions.closeWithoutStaging
    changeIssueStatus config.statuses.manualVerificationOk, config.transitions.close
}

void waitUntilVerificationIsStarted() {
    echo "Waiting for issue status to change to 'code review'..."
    waitUntilIssueStatusIs config.statuses.codeReview
}

void waitUntilCodeReviewIsFinished() {
    echo "Waiting for issue status to change from 'code review'..."
    waitUntilIssueStatusIsNot config.statuses.codeReview
}

boolean isCodeApproved() {
    issueStatusIs config.statuses.codeApproved
}

void waitUntilManualVerificationIsStarted() {
    echo "Waiting for issue status to change to 'manual verification'..."
    waitUntilIssueStatusIs config.statuses.manualVerification
}

void waitUntilManualVerificationIsFinished() {
    echo "Waiting for issue status to change from 'manual verification'..."
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
        int counter = 0
        while (!issueStatusIs(targetStatus)) {
            if (counter == 30) {
                counter = 0
                echo "Still waiting for issue status to change..."
            }
            sleep 10
            counter++
        }
    } catch (FlowInterruptedException e) {
        echo "Waiting was aborted"
        env.jobAborted = "true"
    }
}

private void waitUntilIssueStatusIsNot(def targetStatus) {
    env.jobAborted = 'false'
    try {
        int counter = 0
        while (issueStatusIs(targetStatus)) {
            if (counter == 30) {
                counter = 0
                echo "Still waiting for issue status to change..."
            }
            sleep 10
            counter++
        }
    } catch (FlowInterruptedException e) {
        echo "Waiting was aborted"
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
