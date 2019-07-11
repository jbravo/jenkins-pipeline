import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.DependencyTrack

def call(body) {
    String projectName = JOB_NAME.tokenize('/')[0]
    Components components = new Components(projectName)
    Jira jira = components.jira
    Docker dockerClient = components.docker
    Git git = components.git
    DependencyTrack dependencyTrack = components.dependencyTrack
    String agentImage = 'difi/jenkins-agent'
    String agentArgs = '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
            '--network pipeline_pipeline ' +
            '-v /var/run/docker.sock:/var/run/docker.sock ' +
            '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
            '-u root:root'
    Map params = [:]
    params.parallelMavenDeploy = true
    params.stagingQueue = false
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()
    String verificationLock = projectName + "-verification"
    String stagingLock = projectName + '-staging'
    String productionLock = projectName + '-production'
    if (!params.stagingQueue) {
        stagingLock += "-${BRANCH_NAME}"
    }
    if (params.javaVersion == 9)
        agentImage = 'difi/jenkins-agent-java9'
    if (params.javaVersion == 10)
        agentImage = 'difi/jenkins-agent-java10'
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
            stage('Wait for code reviewer') {
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
            stage('Verification') {
                options {
                    lock resource: verificationLock
                }
                when {
                    beforeAgent true
                    environment name: 'verification', value: 'true'
                }
                stages {
                    stage('Prepare') {
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
                    stage('Deliver (Java)') {
                        when {
                            beforeAgent true
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
                                components.verificationDeliverJava.script(params)
                            }
                        }
                        post {
                            always {
                                junit '**/target/surefire-reports/TEST-*.xml'
                            }
                            failure {
                                script {
                                    components.verificationDeliverJava.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.verificationDeliverJava.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Deliver (Docker)') {
                        when {
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
                    stage('Deploy') {
                        when {
                            beforeAgent true
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
                    stage('Test') {
                        when {
                            beforeAgent true
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
                                components.verificationTests.script(params)
                            }
                        }
                        post {
                            always {
                                script {
                                    dockerClient.removeStack params.verificationEnvironment, env.stackName
                                }
                            }
                            failure {
                                script {
                                    components.verificationTests.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.verificationTests.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Review') {
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
                    stage('Integrate code') {
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
                stages {
                    stage('Deliver (Java)') {
                        agent {
                            docker {
                                label 'slave'
                                image agentImage
                                args agentArgs
                            }
                        }
                        steps {
                            script {
                                components.stagingDeliverJava.script(params)
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.stagingDeliverJava.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.stagingDeliverJava.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Deliver (Docker)') {
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
                    stage('Review') {
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
            stage('Production') {
                options {
                    lock resource: productionLock
                }
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
                stages {
                    stage('Deliver (Java)') {
                        steps {
                            script {
                                components.productionDeliverJava.script(params)
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.productionDeliverJava.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.productionDeliverJava.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Deliver (Docker)') {
                        steps {
                            script {
                                components.productionDeliverDocker.script(params)
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.productionDeliverDocker.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.productionDeliverDocker.abortedScript(params)
                                }
                            }
                        }
                    }
                    stage('Deploy') {
                        options {
                            lock resource: "docker:${params.productionEnvironment}"
                        }
                        steps {
                            script {
                                components.productionDeploy.script(params)
                            }
                        }
                        post {
                            failure {
                                script {
                                    components.productionDeploy.failureScript(params)
                                }
                            }
                            aborted {
                                script {
                                    components.productionDeploy.abortedScript(params)
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
                        if (params.enableDependencyTrack) {
                            dependencyTrack.deleteProject(env.JOB_NAME)
                        }
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
