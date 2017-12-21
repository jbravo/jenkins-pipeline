package no.difi.jenkins.pipeline

String readCommitId() {
    sh(returnStdout: true, script: 'git rev-parse HEAD').trim().take(7)
}

String readCommitMessage() {
    sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
}

void waitForAvailableVerificationSlot(def sshKey) {
    sshagent([sshKey]) {
        while (true) {
            def currentBranchUnderVerification = currentBranchUnderVerification(sshKey)
            if (currentBranchUnderVerification == env.BRANCH_NAME) {
                echo "Verification branch from previous build was not deleted. Fixing..."
                deleteVerificationBranch(sshKey)
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

private String currentBranchUnderVerification(def sshKey) {
    def output = sh returnStdout: true, script: "git ls-remote --heads origin verify/\\*"
    if (output.trim().isEmpty()) return null
    (output =~ /[0-9a-z]+\s+refs\/heads\/verify\/(.*)/)[0][1]
}

String verificationBranch() {
    "verify/${env.BRANCH_NAME}"
}

void checkoutVerificationBranch() {
    sh "git checkout ${verificationBranch()}"
    sh "git reset --hard origin/${verificationBranch()}"
}

void deleteVerificationBranch(def sshKey) {
    echo "Deleting verification branch"
    sshagent([sshKey]) { sh "git push origin --delete ${verificationBranch()}" }
}

void deleteWorkBranch(def sshKey) {
    echo "Deleting work branch"
    sshagent([sshKey]) { sh "git push origin --delete \${BRANCH_NAME}" }
}

void integrateCode(def sshKey) {
    echo "Integrating code"
    checkoutVerificationBranch()
    sshagent([sshKey]) {
        sh 'git push origin HEAD:master'
    }
}
