package no.difi.jenkins.pipeline

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

void createVerificationBranch(String logEntry, String sshKey) {
    sshagent([sshKey]) {
        return sh(returnStdout: true, script: """#!/usr/bin/env bash
    
        __fail() {
            local ret=\$?
            >&2 printf "\u274c \$1\n"
            return \${ret}
        }
    
        __masterBranch() {
            echo \${MASTER_BRANCH:-'master'}
        }
    
        createRevision() {
            git fetch origin \$(__masterBranch) >&2 || { __fail "Failed to update remote tracking master branch"; return 1; }
            git branch --contains origin/\$(__masterBranch) | grep ${BRANCH_NAME} > /dev/null \
                || { >&2 echo "Current branch does not contain entire master branch. Implicit merge will be performed"; }
            local author="\$(git --no-pager show -s --format='%an <%ae>' ${BRANCH_NAME})" || { __fail "Failed to extract author"; return 1; }
            local tmpBranch=\$(date +%s | sha256sum | base64 | head -c 32) || { __fail "Failed to generate UUID for temporary branch name"; return 1; }
            git checkout -b \${tmpBranch} origin/\$(__masterBranch) >/dev/null || { __fail "Failed to create verification branch"; return 1; }
            git config --local user.name 'Jenkins'
            git config --local user.email 'jenkins@difi.no'
            git merge --squash ${BRANCH_NAME} >/dev/null || { __fail "Failed to add work to verification branch"; return 1; }
            output=\$(git commit --allow-empty --author "\${author}" -am "${logEntry}") || { __fail "Failed to commit verification branch: \${output}"; return 1; }
            git config --local --unset user.name
            git config --local --unset user.email
            local commitId
            commitId=\$(git rev-parse HEAD) || { __fail "Failed to find commit id"; }
            git checkout ${BRANCH_NAME} >/dev/null || { __fail "Failed to return to work branch"; return 1; }
            git branch -D \${tmpBranch} >/dev/null || __fail "Failed to delete local temporary branch (run 'git branch -D \${tmpBranch}' to fix it)"
            local output
            output=\$(git push -f -u origin \${commitId}:refs/heads/verify/${BRANCH_NAME} 2>&1) || { __fail "Failed to push tagged verification revision: \${output}"; return 1; }
            echo -n \${commitId}
        }
        
        createRevision
        """)
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
