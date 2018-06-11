package no.difi.jenkins.pipeline

ErrorHandler errorHandler
def sshKey

String readCommitId() {
    sh(returnStdout: true, script: 'git rev-parse HEAD').trim().take(7)
}

String readCommitMessage() {
    sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
}

String repositoryName() {
    String repositoryUrl = sh returnStdout: true, script: 'git remote get-url origin'
    repositoryUrl.tokenize(':/')[-1].tokenize('.')[0].trim()
}

void waitForAvailableVerificationSlot() {
    sshagent([sshKey]) {
        while (true) {
            def currentBranchUnderVerification = currentBranchUnderVerification()
            if (currentBranchUnderVerification == env.BRANCH_NAME) {
                echo "Verification branch from previous build was not deleted. Fixing..."
                deleteVerificationBranch()
            } else if (currentBranchUnderVerification != null) {
                echo "Branch ${currentBranchUnderVerification} is using the verification slot. Waiting 10 seconds..."
                sleep 10
            } else {
                echo "Verification slot is available"
                return
            }
        }
    }
}

void createVerificationBranch(String logEntry) {
    logEntry = logEntry.replaceAll('"', '\\\\"')
    sshagent([sshKey]) {
        return sh(returnStdout: true, script: """#!/usr/bin/env bash
            git fetch origin master >&2 || { >&2 echo "Failed to update remote tracking master branch"; exit 1; }
            git branch --contains origin/master | grep ${BRANCH_NAME} > /dev/null \
                || { >&2 echo "Current branch does not contain entire master branch. Implicit merge will be performed"; }
            author="\$(git --no-pager show -s --format='%an <%ae>' ${BRANCH_NAME})" || { >&2 echo "Failed to extract author"; exit 1; }
            tmpBranch=\$(date +%s | sha256sum | base64 | head -c 32) || { >&2 echo "Failed to generate UUID for temporary branch name"; exit 1; }
            git checkout -b \${tmpBranch} origin/master >/dev/null || { >&2 echo "Failed to create verification branch"; exit 1; }
            git merge --squash ${BRANCH_NAME} >/dev/null || { >&2 echo "Failed to add work to verification branch"; exit 1; }
            output=\$(git commit --allow-empty --author "\${author}" -am "${logEntry}") || { >&2 echo "Failed to commit verification branch: \${output}"; exit 1; }
            commitId=\$(git rev-parse HEAD) || { >&2 echo "Failed to find commit id"; exit 1; }
            git checkout ${BRANCH_NAME} >/dev/null || { >&2 echo "Failed to return to work branch"; exit 1; }
            git branch -D \${tmpBranch} >/dev/null || >&2 echo "Failed to delete local temporary branch (run 'git branch -D \${tmpBranch}' to fix it)"
            output=\$(git push -f -u origin \${commitId}:refs/heads/verify/${BRANCH_NAME} 2>&1) || { >&2 echo "Failed to push tagged verification revision: \${output}"; exit 1; }
            echo -n \${commitId}
        """)
    }
}

private String currentBranchUnderVerification() {
    def output = sh returnStdout: true, script: "git ls-remote --heads origin verify/\\*"
    if (output.trim().isEmpty()) return null
    (output =~ /[0-9a-z]+\s+refs\/heads\/verify\/(.*)/)[0][1]
}

String verificationBranch() {
    "verify/${env.BRANCH_NAME}"
}

void checkoutVerificationBranch() {
    sh "git checkout ${verificationBranch()}"
}

void resetVerificationBranchToOrigin() {
    sh "git checkout ${verificationBranch()} && git reset --hard origin/${verificationBranch()} && git checkout -"
}

void deleteVerificationBranch() {
    echo "Deleting verification branch"
    sshagent([sshKey]) {
        int status = sh returnStatus: true, script: "git push origin --delete ${verificationBranch()}"
        if (status != 0) {
            echo "Failed to delete verification branch ${verificationBranch()}. Verification for this project will be locked to this work branch (${env.BRANCH_NAME})"
        }
    }
}

void deleteWorkBranch() {
    echo "Deleting work branch"
    sshagent([sshKey]) { sh "git push origin --delete \${BRANCH_NAME}" }
}

void integrateCode() {
    echo "Integrating code"
    checkoutVerificationBranch()
    sshagent([sshKey]) {
        sh 'git push origin HEAD:master'
    }
}

boolean isIntegrated(String issueId) {
    fetchMasterFromOrigin()
    int status = sh(returnStatus: true, script: """#!/usr/bin/env bash    
        log=\$(git log --format=%B origin/master) || exit 2
        echo \${log} | grep -qP "^(((.{15,}: )?(work)/)|.{15,}\\|)${issueId}:"
    """)
    switch (status) {
        case 0: return true
        case 1: return false
        default: errorHandler.trigger "Failed to check if ${issueId} was already integrated"
    }
}

private void fetchMasterFromOrigin() {
    sshagent([sshKey]) {
        int status = sh(returnStatus: true, script: 'git fetch origin master')
        if (status != 0)
            errorHandler.trigger "Failed to fetch master branch from origin"
    }
}
