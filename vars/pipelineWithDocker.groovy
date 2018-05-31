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
    String stagingLock = projectName + '-staging'
    String productionLock = projectName + '-production'
    String agentImage = 'difi/jenkins-agent'
    String agentArgs = '--network pipeline_pipeline ' +
            '-v /var/run/docker.sock:/var/run/docker.sock ' +
            '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
            '-u root:root'
    Map params = [:]
    params.stagingQueue = false
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()
    if (!params.stagingQueue) {
        stagingLock += "-${BRANCH_NAME}"
    }
    node() {
        checkout scm
        env.verification = 'false'
        env.startVerification = 'false'
        String commitMessage = git.readCommitMessage()
        if (commitMessage.startsWith('ready!')) {
            env.verification = 'true'
        } else if (commitMessage.startsWith('integrate!')) {
            env.verification = 'true'
            params.verificationEnvironment = null
            params.stagingEnvironment = null
            params.productionEnvironment = null
        } else {
            stagingLock += '-no-lock'
            productionLock += '-no-lock'
        }
        if (commitMessage.startsWith('ready!!') || commitMessage.startsWith('integrate!!'))
            env.startVerification = 'true'
    }

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
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        components.checkBuild.script(params)
                    }
                }
            }
            stage('Wait for verification to start') {
                when {
                    environment name: 'verification', value: 'true'
                }
                steps {
                    script {
                        components.waitForVerificationToStart.script()
                    }
                }
            }
            stage('Wait for verification slot') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        components.waitForVerificationSlot.script(params)
                    }
                }
                post {
                    failure {
                        script {
                            components.waitForVerificationSlot.failureScript()
                        }
                    }
                    aborted {
                        script {
                            components.waitForVerificationSlot.abortedScript()
                        }
                    }
                }
            }
            stage('Prepare verification') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                }
                environment {
                    crucible = credentials('crucible')
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        components.prepareVerification.script(params)
                    }
                }
                post {
                    failure {
                        script {
                            components.prepareVerification.failureScript(params)
                        }
                    }
                    aborted {
                        script {
                            components.prepareVerification.abortedScript(params)
                        }
                    }
                }
            }
            stage('Verification deliver') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.verificationEnvironment != null }
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
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
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.verificationEnvironment != null }
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        env.stackName = dockerClient.uniqueStackName()
                        dockerClient.deployStack params.verificationEnvironment, env.stackName, env.version
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            dockerClient.removeStack params.verificationEnvironment, env.stackName
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.verificationEnvironment, env.version
                            dockerClient.removeStack params.verificationEnvironment, env.stackName
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Verification tests') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.verificationEnvironment != null }
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
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
                when {
                    environment name: 'verification', value: 'true'
                }
                steps {
                    script {
                        jira.waitUntilCodeReviewIsFinished()
                    }
                }
            }
            stage('Staging deliver') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.stagingEnvironment != null }
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        jira.failIfCodeNotApproved()
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
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        jira.failIfCodeNotApproved()
                        jira.createAndSetFixVersion env.version
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
                    lock resource: stagingLock
                }
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.stagingEnvironment != null }
                }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "docker:${params.stagingEnvironment}"
                        }
                        agent {
                            docker {
                                label 'slave'
                                image agentImage
                                args agentArgs
                            }
                        }
                        steps {
                            script {
                                git.checkoutVerificationBranch()
                                jira.updateIssuesForManualVerification env.version, env.sourceCodeRepository
                                dockerClient.deployStack params.stagingEnvironment, params.stackName, env.version
                                jira.startManualVerification()
                            }
                        }
                        post {
                            failure {
                                script {
                                    jira.stagingFailed()
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                            aborted {
                                script {
                                    jira.stagingFailed()
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                        }
                    }
                    stage('Wait for approval') {
                        steps {
                            script {
                                if (!jira.waitUntilManualVerificationIsStarted()) return // Not needen when lock on sequential stages is supported
                                if (!jira.waitUntilManualVerificationIsFinishedAndAssertSuccess(env.sourceCodeRepository)) return
                                if (!jira.fixVersions().contains(env.version)) {
                                    env.verification = 'false'
                                    node() {
                                        checkout scm
                                        git.deleteWorkBranch(params.gitSshKey)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Production deliver') {
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.productionEnvironment != null }
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        failIfJobIsAborted()
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
                    lock resource: productionLock
                }
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                    expression { params.productionEnvironment != null }
                }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "docker:${params.productionEnvironment}"
                        }
                        agent {
                            docker {
                                label 'slave'
                                image agentImage
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
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                }
                agent {
                    docker {
                        label 'slave'
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        failIfJobIsAborted()
                        jira.close env.version, env.sourceCodeRepository
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
