import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.AWS
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git

def call(body) {
    Components components = new Components()
    Jira jira = components.jira
    AWS aws = components.aws
    Docker dockerClient = components.docker
    Git git = components.git
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
                    script {
                        currentBuild.description = "Building from commit " + git.readCommitId()
                        jira.startWork()
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
                    script {
                        jira.readyForCodeReview()
                        if (env.startVerification == 'true') {
                            jira.startVerification()
                        }
                        jira.waitUntilVerificationIsStarted()
                    }
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
                        script {
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            jira.resumeWork()
                        }
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
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            jira.resumeWork()
                        }
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
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.stagingDockerRegistry
                            jira.resumeWork()
                        }
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
                        script {
                            jira.resumeWork()
                            git.deleteVerificationBranch(params.gitSshKey)
                            if (aws.infrastructureSupported()) {
                                aws.removeInfrastructure env.version, params.stackName
                            } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                                dockerClient.removeStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                            }
                        }
                    }
                    aborted {
                        script {
                            jira.resumeWork()
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
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Wait for code review to finish') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && env.skipWait == 'false'} }
                steps {
                    script {
                        jira.waitUntilCodeReviewIsFinished()
                        env.codeApproved = String.valueOf(jira.isCodeApproved())
                    }
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
                        script {
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            jira.resumeWork()
                        }
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
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished env.version, params.dockerRegistry
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Wait for manual verification to start') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && env.skipWait == 'false'} }
                steps {
                    script {
                        jira.waitUntilManualVerificationIsStarted()
                    }
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
                    script {
                        jira.waitUntilManualVerificationIsFinished()
                        failIfJobIsAborted()
                        jira.assertManualVerificationWasSuccessful()
                    }
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
