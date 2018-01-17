import no.difi.jenkins.pipeline.AWS
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git

def call(body) {
    AWS aws = new AWS()
    Docker dockerClient = new Docker()
    Git git = new Git()
    Map params= [:]
    params.stagingDockerRegistry = 'StagingLocal'
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    pipeline {
        agent none
        options {
            disableConcurrentBuilds()
            ansiColor('xterm')
            timestamps()
        }
        stages {
            stage('Check build') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    transitionIssue env.ISSUE_STATUS_OPEN, env.ISSUE_TRANSITION_START
                    transitionIssue '10717', '401' // env.ISSUE_STATUS_READY_FOR_VERIFICATION env.ISSUE_TRANSITION_CANCEL_VERIFICATION
                    ensureIssueStatusIs env.ISSUE_STATUS_IN_PROGRESS
                    script {
                        currentBuild.description = "Building from commit " + git.readCommitId()
                        env.verification = 'false'
                        env.startVerification = 'false'
                        env.skipWait = 'false'
                        String commitMessage = git.readCommitMessage()
                        if (commitMessage.startsWith('ready!'))
                            env.verification = 'true'
                        if (commitMessage.startsWith('ready!!'))
                            env.startVerification = 'true'
                        if (commitMessage.startsWith('ready!!!'))
                            env.skipWait = 'true'
                        dockerClient.verify()
                    }
                }
            }
            stage('Wait for verification to start') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                steps {
                    transitionIssue env.ISSUE_TRANSITION_READY_FOR_CODE_REVIEW
                    script {
                        if (env.startVerification == 'true') {
                            transitionIssue '291'
                        }
                    }
                    waitUntilIssueStatusIs env.ISSUE_STATUS_CODE_REVIEW
                }
            }
            stage('Wait for verification slot') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    echo "Waiting for available verification slot..."
                    waitForAvailableVerificationSlot(params.gitSshKey)
                    failIfJobIsAborted()
                }
                post {
                    failure {
                        transitionIssue '10717', '401' // env.ISSUE_STATUS_READY_FOR_VERIFICATION env.ISSUE_TRANSITION_CANCEL_VERIFICATION
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        transitionIssue '10717', '401' // env.ISSUE_STATUS_READY_FOR_VERIFICATION env.ISSUE_TRANSITION_CANCEL_VERIFICATION
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Prepare verification') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    crucible = credentials('crucible')
                }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    prepareVerification params.gitSshKey, env.CRUCIBLE_URL, env.CRUCIBLE_PROJECT_KEY, env.crucible_USR, env.crucible_PSW
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Build artifacts') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish env.version, params.stagingDockerRegistry
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.stagingDockerRegistry
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.stagingDockerRegistry
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Deploy for verification') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                environment {
                    aws = credentials('aws')
                }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        if (aws.infrastructureSupported()) {
                            String verificationHostName = aws.createInfrastructure env.version, params.verificationHostSshKey, env.aws_USR, env.aws_PSW, params.stackName
                            dockerClient.deployStack params.verificationHostSshKey, "ubuntu", verificationHostName, params.stagingDockerRegistry, params.stackName, env.version
                        } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                            env.stackName = new Random().nextLong().abs()
                            dockerClient.deployStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, params.stagingDockerRegistry, env.stackName, env.version
                        }
                    }
                }
                post {
                    always {
                        script {
                            dockerClient.deletePublished env.version, params.stagingDockerRegistry
                        }
                    }
                    failure {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            if (aws.infrastructureSupported()) {
                                aws.removeInfrastructure env.version, params.stackName
                            } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                                dockerClient.removeStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                            }
                        }
                    }
                    aborted {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            if (aws.infrastructureSupported()) {
                                aws.removeInfrastructure env.version, params.stackName
                            } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                                dockerClient.removeStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                            }
                        }
                    }
                }
            }
            stage('Verify behaviour') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                    }
                }
                post {
                    always {
                        script {
                            if (aws.infrastructureSupported()) {
                                aws.removeInfrastructure env.version, params.stackName
                            } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                                dockerClient.removeStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                            }
                        }
                    }
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Wait for code review to finish') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && env.skipWait == 'false'} }
                steps {
                    waitForCodeReviewToFinish()
                }
            }
            stage('Integrate code') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    failIfCodeNotApproved()
                    script {
                        git.integrateCode params.gitSshKey
                    }
                }
                post {
                    always {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                        }
                    }
                    success {
                        script {
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                    failure {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Publish artifacts') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true'} }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish env.version, params.dockerRegistry
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.dockerRegistry
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.dockerRegistry
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                }
            }
            stage('Wait for manual verification to start') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && env.skipWait == 'false'} }
                steps {
                    waitUntilIssueStatusIs env.ISSUE_STATUS_MANUAL_VERIFICATION
                }
            }
            stage('Deploy for manual verification') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        if (dockerClient.stackSupported() && params.verificationHostName != null) {
                            dockerClient.deployStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, params.dockerRegistry, params.stackName, env.version
                        }
                    }
                }
            }
            stage('Wait for manual verification to finish') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && env.skipWait == 'false'} }
                steps {
                    waitUntilIssueStatusIsNot env.ISSUE_STATUS_MANUAL_VERIFICATION
                    failIfJobIsAborted()
                    ensureIssueStatusIs env.ISSUE_STATUS_MANUAL_VERIFICATION_OK
                }
            }
            stage('Deploy for production') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        if (dockerClient.stackSupported() && params.productionHostName != null) {
                            dockerClient.deployStack params.productionHostSshKey, params.productionHostUser, params.productionHostName, params.dockerRegistry, params.stackName, env.version
                        }
                    }
                }
            }
        }
        post {
            success {
                echo "Success"
                notifySuccess()
            }
            unstable {
                echo "Unstable"
                notifyUnstable()
            }
            failure {
                echo "Failure"
                notifyFailed()
            }
            aborted {
                echo "Aborted"
                notifyFailed()
            }
            always {
                echo "Build finished"
            }
        }
    }

}


def notifyFailed() {
    emailext (
            subject: "FAILED: '${env.JOB_NAME}'",
            body: """<p>FAILED: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' feilet.</p>
            <p><b>Konsoll output:</b><br/>
            <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p>""",
            recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}

def notifyUnstable() {
    emailext (
            subject: "UNSTABLE: '${env.JOB_NAME}'",
            body: """<p>UNSTABLE: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' er ustabilt.</p>
            <p><b>Konsoll output:</b><br/>
            <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p>""",
            recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}

def notifySuccess() {
    if (isPreviousBuildFailOrUnstable()) {
        emailext (
                subject: "SUCCESS: '${env.JOB_NAME}'",
                body: """<p>SUCCESS: Bygg '${env.JOB_NAME} [${env.BUILD_NUMBER}]' er oppe og snurrer igjen.</p>""",
                recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']]
        )
    }
}

boolean isPreviousBuildFailOrUnstable() {
    if(!hudson.model.Result.SUCCESS.equals(currentBuild.rawBuild.getPreviousBuild()?.getResult())) {
        return true
    }
    return false
}
