import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git

def call(body) {
    String projectName = JOB_NAME.tokenize('/')[0]
    Components components = new Components(projectName)
    Jira jira = components.jira
    Docker dockerClient = components.docker
    Git git = components.git
    String stagingLock = projectName + '-staging'
    String productionLock = projectName + '-production'
    String agentImage = 'difi/jenkins-agent'
    String agentArgs = '--network pipeline_pipeline ' +
            '-v /var/run/docker.sock:/var/run/docker.sock ' +
            '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
            '-u root:root'
    Map params = [:]
    params.stagingQueue = false
    params.stagingEnvironmentType = 'docker'
    params.productionEnvironmentType = 'docker'
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
                post {
                    failure {
                        script {
                            components.waitForVerificationToStart.failureScript()
                        }
                    }
                    aborted {
                        script {
                            components.waitForVerificationToStart.abortedScript()
                        }
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
                        components.verificationDeliverDocker.script(params)
                    }
                }
                post {
                    failure {
                        script {
                            components.verificationDeliverDocker.failureScript(params)
                        }
                    }
                    aborted {
                        script {
                            components.verificationDeliverDocker.abortedScript(params)
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
                        components.verificationDeploy.script(params)
                    }
                }
                post {
                    failure {
                        script {
                            components.verificationDeploy.failureScript(params)
                        }
                    }
                    aborted {
                        script {
                            components.verificationDeploy.abortedScript(params)
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
                        components.waitForCodeReviewToFinish.script()
                    }
                }
                post {
                    failure {
                        script {
                            components.waitForCodeReviewToFinish.failureScript()
                        }
                    }
                    aborted {
                        script {
                            components.waitForCodeReviewToFinish.abortedScript()
                        }
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
                    script {
                        components.stagingDeliverDocker.script(params)
                    }
                }
                post {
                    failure {
                        script {
                            components.stagingDeliverDocker.failureScript(params)
                        }
                    }
                    aborted {
                        script {
                            components.stagingDeliverDocker.abortedScript(params)
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
                    script {
                        components.integrateCode.script()
                    }
                }
                post {
                    failure {
                        script {
                            components.integrateCode.failureScript(params)
                        }
                    }
                    aborted {
                        script {
                            components.integrateCode.abortedScript(params)
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
                            lock resource: "${params.stagingEnvironmentType}:${params.stagingEnvironment}"
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
                                components.stagingDeploy.script(params)
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.stagingDeploy.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.stagingDeploy.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Wait for approval') {
                        steps {
                            script {
                                components.waitForApproval.script()
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.waitForApproval.failureScript()
                                }
                            }
                            aborted {
                                script {
                                    components.waitForApproval.abortedScript()
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
                        git.checkoutVerificationBranch()
                        dockerClient.buildAndPublish params.productionEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            dockerClient.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch()
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch()
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
                            lock resource: "${params.productionEnvironmentType}:${params.productionEnvironment}"
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
                                    git.deleteWorkBranch()
                                }
                            }
                            aborted {
                                script {
                                    git.deleteWorkBranch()
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
                        jira.close env.version, env.sourceCodeRepository
                        git.deleteWorkBranch()
                    }
                }
            }
        }
        post {
            success {
                script {
                    components.pipeline.successScript()
                }
            }
            unstable {
                script {
                    components.pipeline.unstableScript()
                }
            }
            failure {
                script {
                    components.pipeline.failureScript()
                }
            }
            aborted {
                script {
                    components.pipeline.abortedScript()
                }
            }
            always {
                script {
                    components.pipeline.alwaysScript()
                }
            }
        }
    }

}
