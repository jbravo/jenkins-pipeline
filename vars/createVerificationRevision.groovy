String call(String logEntry, String gitSshKey) {

    sshagent([gitSshKey]) {
        return sh(returnStdout: true, script: """
        #!/usr/bin/env bash
    
        __fail() {
            local ret=\$?
            >&2 printf "\u274c \$1\n"
            return \${ret}
        }
    
        __masterBranch() {
            echo \${MASTER_BRANCH:-'master'}
        }
    
        __currentBranch() {
            git symbolic-ref --short -q HEAD
        }
        
        __verificationPrefix() {
            echo -n "verify/"
        }
        
        __shortVerificationReference() {
            local workBranch=\$1
            echo -n "\$(__verificationPrefix)\${workBranch}"
        }
        
        __verificationReference() {
            local workBranch=\$1
            echo -n "refs/heads/\$(__shortVerificationReference \${workBranch})"
        }
        
        __currentVerificationBranch() {
            local output
            output=\$(git ls-remote --heads --exit-code origin \$(__verificationPrefix)\\*)
            [[ \$? -eq 2 ]] || {
                pattern="[0-9a-z]+[[:space:]]+refs/heads/\$(__verificationPrefix)(.*)"
                [[ \${output} =~ \${pattern} ]]
                echo \${BASH_REMATCH[1]}
                return 0
            }
            return 1
        }
    
        __availableVerificationSlot() {
            local branch
            branch=\$(__currentVerificationBranch) && { echo "Branch \${branch} is using the verification slot"; return 1; } || return 0
        }
    
        __isSelfVerifying() {
            local output
            >/dev/null git ls-remote --heads --exit-code origin \$(__shortVerificationReference \$(__currentBranch))
        }
        
        createRevision() {
            local logEntry=\$1
            __isSelfVerifying && { >&2 echo "Current branch is already under verification, try again later"; return 1; }
            __availableVerificationSlot || { __fail "Branch \$(__currentVerificationBranch) is currently under verification, try again later"; return 1; }
            git fetch origin \$(__masterBranch) >&2 || { __fail "Failed to update remote tracking master branch"; return 1; }
            git branch --contains origin/\$(__masterBranch) | grep \$(__currentBranch) > /dev/null \
                || { >&2 echo "Current branch does not contain entire master branch. Implicit merge will be performed"; }
            local workBranch=\$(__currentBranch)
            local author=\$(git --no-pager show -s --format='%an <%ae>' \${workBranch}) || { __fail "Failed to extract author"; return 1; }
            local tmpBranch=\$(date +%s | sha256sum | base64 | head -c 32) || { __fail "Failed to generate UUID for temporary branch name"; return 1; }
            git checkout -b \${tmpBranch} origin/\$(__masterBranch) >/dev/null || { __fail "Failed to create verification branch"; return 1; }
            git config --local user.name 'Jenkins'
            git config --local user.email 'jenkins@difi.no'
            git merge --squash \${workBranch} >/dev/null || { __fail "Failed to add work to verification branch"; return 1; }
            output=\$(git commit --allow-empty --author "\${author}" -am "\${logEntry}") || { __fail "Failed to commit verification branch: \${output}"; return 1; }
            git config --local --unset user.name
            git config --local --unset user.email
            local commitId
            commitId=\$(git rev-parse HEAD) || { __fail "Failed to find commit id"; }
            git checkout \${workBranch} >/dev/null || { __fail "Failed to return to work branch"; return 1; }
            git branch -D \${tmpBranch} >/dev/null || __fail "Failed to delete local temporary branch (run 'git branch -D \${tmpBranch}' to fix it)"
            local output
            output=\$(git push -f -u origin \${commitId}:\$(__verificationReference \${workBranch}) 2>&1) || { __fail "Failed to push tagged verification revision: \${output}"; return 1; }
            echo -n \${commitId}
        }
        
        createRevision "${logEntry}"
        """)
    }
}