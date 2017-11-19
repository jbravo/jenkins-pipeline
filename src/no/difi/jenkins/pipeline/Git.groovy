package no.difi.jenkins.pipeline

String readCommitId() {
    sh(returnStdout: true, script: 'git rev-parse HEAD').trim().take(7)
}

String readCommitMessage() {
    sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
}

String currentVerificationBranch(def sshKey) {
    sshagent([sshKey]) {
        String branch = sh returnStdout: true, script: """
        #!/usr/bin/env bash
        set +e
    
        output=\$(git ls-remote --heads --exit-code origin verify/\\*)
        [[ \$? -eq 2 ]] || {
            pattern="[0-9a-z]+[[:space:]]+refs/heads/verify/(.*)"
            [[ \${output} =~ \${pattern} ]]
            echo \${BASH_REMATCH[1]}
        }
        """
        branch.trim().isEmpty() ? null : branch.trim()
    }
}

void checkoutVerificationBranch() {
    sh "git checkout verify/\${BRANCH_NAME}"
    sh "git reset --hard origin/verify/\${BRANCH_NAME}"
}

void deleteVerificationBranch(def sshKey) {
    echo "Deleting verification branch"
    sshagent([sshKey]) { sh "git push origin --delete verify/\${BRANCH_NAME}" }
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
