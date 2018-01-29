import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git

def call(body) {
    Components components = new Components()
    Jira jira = components.jira
    Docker dockerClient = components.docker
    Git git = components.git
    String projectName = JOB_NAME.tokenize('/')[0]
    String agentArgs = '--network pipeline_pipeline ' +
            '-v /var/run/docker.sock:/var/run/docker.sock ' +
            '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
            '-u root:root'
    Map params = [:]
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
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        currentBuild.description = "Building from commit " + git.readCommitId()
                        jira.startWork()
                        env.verification = 'false'
                        env.startVerification = 'false'
                        String commitMessage = git.readCommitMessage()
                        if (commitMessage.startsWith('ready!'))
                            env.verification = 'true'
                        if (commitMessage.startsWith('ready!!'))
                            env.startVerification = 'true'
                        dockerClient.verify()
                    }
                }
            }
            stage('Wait for verification to start') {
                when { expression { env.verification == 'true' } }
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
                when { expression { env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
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
                when { expression { env.verification == 'true' } }
                environment {
                    crucible = credentials('crucible')
                }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
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
            stage('Verification deliver') {
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish params.verificationEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Verification deploy') {
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        env.stackName = dockerClient.deployStack params.verificationEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Verification tests') {
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                    }
                }
            }
            stage('Wait for code review to finish') {
                when { expression { env.verification == 'true' } }
                steps {
                    script {
                        jira.waitUntilCodeReviewIsFinished()
                        env.codeApproved = String.valueOf(jira.isCodeApproved())
                    }
                }
            }
            stage('Staging deliver') {
                when { expression { env.verification == 'true' && params.stagingEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish params.stagingEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            git.deleteVerificationBranch(params.gitSshKey)
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            git.deleteVerificationBranch(params.gitSshKey)
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Integrate code') {
                when { expression { env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
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
                    failure {
                        script {
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Staging') {
                options {
                    lock resource: projectName
                }
                when { expression { env.verification == 'true' && params.stagingEnvironment != null } }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "docker:${params.stagingEnvironment}"
                        }
                        agent {
                            docker {
                                label 'slave'
                                image 'difi/jenkins-agent'
                                args agentArgs
                            }
                        }
                        steps {
                            script {
                                git.checkoutVerificationBranch()
                                dockerClient.deployStack params.stagingEnvironment, params.stackName, env.version
                                jira.startManualVerification()
                            }
                        }
                        post {
                            failure {
                                script {
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                            aborted {
                                script {
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                        }
                    }
                    stage('Wait for approval') {
                        steps {
                            script {
                                jira.waitUntilManualVerificationIsStarted()
                                failIfJobIsAborted()
                                jira.waitUntilManualVerificationIsFinished()
                                failIfJobIsAborted()
                                jira.assertManualVerificationWasSuccessful()
                            }
                        }
                    }
                }
            }
            stage('Production deliver') {
                when { expression { env.verification == 'true' && params.productionEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish params.productionEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            dockerClient.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                }
            }
            stage('Production') {
                options {
                    lock resource: projectName
                }
                when { expression { env.verification == 'true' && params.productionEnvironment != null } }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "docker:${params.productionEnvironment}"
                        }
                        agent {
                            docker {
                                label 'slave'
                                image 'difi/jenkins-agent'
                                args agentArgs
                            }
                        }
                        steps {
                            script {
                                git.checkoutVerificationBranch()
                                dockerClient.deployStack params.productionEnvironment, params.stackName, env.version
                            }
                        }
                        post {
                            failure {
                                script {
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                            aborted {
                                script {
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                        }
                    }
                }
            }
            stage('End') {
                when { expression { env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args agentArgs
                    }
                }
                steps {
                    script {
                        jira.close()
                        git.deleteWorkBranch(params.gitSshKey)
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
