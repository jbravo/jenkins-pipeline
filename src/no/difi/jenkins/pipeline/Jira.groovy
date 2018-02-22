package no.difi.jenkins.pipeline

import hudson.AbortException
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

import static java.util.stream.Collectors.joining

Map config
ErrorHandler errorHandler

void setComponent(String name) {
    try {
        doSetComponent name
    } catch (AbortException e) {
        echo "Seems like component '${name} does not exist. Creating it..."
        newComponent name
        try {
            doSetComponent name
        } catch (e2) {
            errorHandler.trigger "Failed to set component on issue: ${e2}"
        }
    } catch (e) {
        errorHandler.trigger "Failed to set component on issue: ${e}"
    }
}

private void doSetComponent(String name) {
    jiraEditIssue idOrKey: issueId(), issue: [fields: [components: [[name: name]]]]
}

void newComponent(String name) {
    try {
        jiraNewComponent component: [name: name, project: projectKey()]
    } catch (e) {
        errorHandler.trigger "Failed to create new component '$name': ${e}"
    }
}

private List<String> issuesWithStatus(def status, String component) {
    try {
        def response = jiraJqlSearch jql: "status = ${status} and component = '${component}'"
        response.data.issues['key'] as List<String>
    } catch (e) {
        errorHandler.trigger "Failed to get list of issues with status ${status}: ${e}"
    }
}

/**
 * Issues that should be manually verified are those that belong to the given component and have a status indicating
 * they are under manual verification or have been manually verified without success but are not closed.
 * These issues will have their status set to <i>manualVerification</i> and the given fix version in order to be
 * manually verified again.
 * @param fixVersion
 * @param component
 */
void updateIssuesForManualVerification(def version, String component) {
    issuesWithStatus(config.statuses.manualVerification, component).each { issueId ->
        fixVersion issueId, version
    }
    issuesWithStatus(config.statuses.manualVerificationOk, component).each { issueId ->
        fixVersion issueId, version
    }
    issuesWithStatus(config.statuses.manualVerificationFailed, component).each { issueId ->
        fixVersion issueId, version
        changeIssueStatus issueId, config.transitions.retryManualVerificationFromFailure
    }
}

String issueId() {
    env.BRANCH_NAME.tokenize('/')[-1]
}

private void newVersion(def version) {
    try {
        jiraNewVersion version: [name: version, project: projectKey()]
    } catch (e) {
        errorHandler.trigger "Failed to create new fix version: ${e.message}"
    }
}

void createAndSetFixVersion(def version) {
    newVersion version
    fixVersion version
}

void fixVersion(def version) {
    fixVersion issueId(), version
}

void fixVersion(String issueId, def version) {
    List<String> fixVersionsToKeep = fixVersions(issueId).findAll({ it -> !it.matches("\\d{4}-\\d{2}-\\d{2}-.*")})
    fixVersionsToKeep.add((String)version)
    def versions = fixVersionsToKeep.collect({it -> [name: it]})
    try {
        jiraEditIssue idOrKey: issueId, issue: [fields: [fixVersions: versions]]
    } catch (e) {
        errorHandler.trigger "Failed to set fix version ${version} on issue ${issueId}: ${e.message}"
    }
}

List<String> fixVersions() {
    return fixVersions(issueId())
}


List<String> fixVersions(String issueId) {
    try {
        issueFields(issueId)['fixVersions']['name'] as List
    } catch (e) {
        errorHandler.trigger "Failed to get fix version: ${e.message}"
    }
}

Map issueFields(def issueId) {
    try {
        jiraGetIssue(idOrKey: issueId).data.fields
    } catch (e) {
        errorHandler.trigger "Failed to get issue fields: ${e.message}"
    }
}

String issueStatus(def issueId) {
    try {
        issueStatus issueFields(issueId)
    } catch (e) {
        errorHandler.trigger "Failed to get issue status: ${e.message}"
    }
}

boolean issueStatusIs(def targetStatus) {
    issueStatusIs issueId(), targetStatus
}

boolean issueStatusIs(def issueId, def targetStatus) {
    issueStatus(issueId) == targetStatus as String
}

String issueSummary() {
    try {
        issueSummary issueFields(issueId())
    } catch (e) {
        errorHandler.trigger "Failed to get issue summary: ${e.message}"
    }
}

String projectKey() {
    projectKey issueId()
}

String projectKey(String issueId) {
    try {
        projectKey issueFields(issueId)
    } catch (e) {
        errorHandler.trigger "Failed to get issue summary: ${e.message}"
    }
}

static String issueStatus(Map issueFields) {
    issueFields['status']['id']
}

static String issueSummary(Map issueFields) {
    issueFields['summary']
}


static String projectKey(Map issueFields) {
    issueFields['project']['key']
}

static String components(Map issueFields) {
    issueFields['components']
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

void waitUntilManualVerificationIsFinishedAndAssertSuccess(String component) {
    List<String> issues = issuesWithStatus config.statuses.manualVerification, component
    echo """Waiting for following issues to complete manual verification:
            ${issues.stream().sorted().collect(joining("\n"))}
    """
    issues.each { issueId ->
        echo "Waiting for issue status to change from 'manual verification' for ${issueId}..."
        waitUntilIssueStatusIsNot issueId, config.statuses.manualVerification
    }
    List<String> failedIssues = new ArrayList<>()
    issues.each { issueId ->
        echo "Checking issue status for ${issueId}..."
        if (issueStatus(issueId) == config.statuses.manualVerificationFailed) {
            echo "Issue ${issueId} failed verification -- creating a bug issue"
            createBugForFailedVerification issueId
            failedIssues.add issueId
        }
    }
    if (!failedIssues.isEmpty()) {
        errorHandler.trigger "Following issues failed verification: ${failedIssues}"
    } else {
        echo "All issues were successfully verified: ${issues}"
    }
}

private void createBugForFailedVerification(def issueId) {
    try {
        Map issueFields = issueFields issueId
        def response = jiraNewIssue issue: [fields: [
                project: [key: projectKey(issueFields)],
                summary: "Manual verification of ${issueId} failed",
                issuetype: [id: config.issues.bug],
                components: components(issueFields),
                description: "Manual verification failed for issue ${issueId}. Check the build log at ${BUILD_URL}console."
        ]]
        jiraLinkIssues type: config.issueLinks.failedVerification, inwardKey: response.data.key, outwardKey: issueId
    } catch (e) {
        echo "Failed to create bug for issue ${issueId} that failed verification: ${e}"
    }
}

private void changeIssueStatus(def transitionId) {
    changeIssueStatus issueId(), transitionId
}

private void changeIssueStatus(String issueId, def transitionId) {
    try {
        jiraTransitionIssue idOrKey: issueId, input: [transition: [id: transitionId]]
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
    waitUntilIssueStatusIsNot issueId(), targetStatus
}

private void waitUntilIssueStatusIsNot(def issueId, def targetStatus) {
    env.jobAborted = 'false'
    try {
        int counter = 0
        while (issueStatusIs(issueId, targetStatus)) {
            if (counter == 30) {
                counter = 0
                echo "Still waiting for issue status to change for issue ${issueId}..."
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
    ensureIssueStatusIs issueId(), issueStatus
}

private void ensureIssueStatusIs(def issueId, def issueStatus) {
    if (!issueStatusIs(issueId, issueStatus))
        errorHandler.trigger "Issue status for ${issueId} is not ${issueStatus}"
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
