import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.Puppet
import no.difi.jenkins.pipeline.VerificationTestResult

def call(body) {
    Components components = new Components()
    Jira jira = components.jira
    Docker dockerClient = components.docker
    Git git = components.git
    Maven maven = components.maven
    Puppet puppet = components.puppet
    String projectName = JOB_NAME.tokenize('/')[0]
    String stagingLock = projectName + '-staging'
    String productionLock = projectName + '-production'
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
    if (params.javaVersion == 9)
        agentImage = 'difi/jenkins-agent-java9'
    if (!params.stagingQueue) {
        stagingLock += "-${BRANCH_NAME}"
    }
    node() {
        checkout scm
        env.verification = 'false'
        env.startVerification = 'false'
        String commitMessage = git.readCommitMessage()
        if (commitMessage.startsWith('ready!'))
            env.verification = 'true'
        else {
            stagingLock += '-no-lock'
            productionLock += '-no-lock'
        }
        if (commitMessage.startsWith('ready!!'))
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
                        currentBuild.description = "Building from commit " + git.readCommitId()
                        env.sourceCodeRepository = env.GIT_URL
                        jira.setSourceCodeRepository env.sourceCodeRepository
                        jira.startWork()
                        maven.verify params.MAVEN_OPTS
                    }
                }
                post {
                    always {
                        junit '**/target/surefire-reports/TEST-*.xml'
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
                        image agentImage
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
                        image agentImage
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
            stage('Verification deliver (Java)') {
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
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
                        maven.deliver(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy, params.verificationEnvironment)
                    }
                }
                post {
                    always {
                        junit '**/target/surefire-reports/TEST-*.xml'
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
            stage('Verification deliver (Docker)') {
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
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
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
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
                when { expression { env.verification == 'true' && params.verificationEnvironment != null } }
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
                        if (maven.verificationTestsSupported(params.verificationEnvironment)) {
                            VerificationTestResult result = maven.runVerificationTests params.verificationEnvironment, env.stackName
                            jira.addComment(
                                    "Verifikasjonstester utført: [Rapport|${result.reportUrl()}] og [byggstatus|${env.BUILD_URL}]",
                            )
                            if (!result.success())
                                error 'Verification tests failed'
                        }
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
                when { expression { env.verification == 'true' } }
                steps {
                    script {
                        jira.waitUntilCodeReviewIsFinished()
                    }
                }
            }
            stage('Staging deliver (Java)') {
                when { expression { env.verification == 'true' && params.stagingEnvironment != null } }
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
                        maven.deliver(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy, params.stagingEnvironment)
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            maven.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            maven.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Staging deliver (Docker)') {
                when { expression { env.verification == 'true' && params.stagingEnvironment != null } }
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
                        dockerClient.buildAndPublish params.stagingEnvironment, env.version
                    }
                }
                post {
                    failure {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            maven.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            maven.deletePublished params.stagingEnvironment, env.version
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
                            maven.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.stagingEnvironment, env.version
                            maven.deletePublished params.stagingEnvironment, env.version
                            jira.resumeWork()
                        }
                    }
                }
            }
            stage('Staging') {
                options {
                    lock resource: stagingLock
                }
                when { expression { env.verification == 'true' && params.stagingEnvironment != null } }
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
                                git.checkoutVerificationBranch()
                                jira.updateIssuesForManualVerification env.version, env.sourceCodeRepository
                                if (params.stagingEnvironmentType == 'puppet') {
                                    puppet.deploy params.stagingEnvironment, env.version, params.puppetModules, params.librarianModules, params.puppetApplyList
                                } else if (params.stagingEnvironmentType == 'docker') {
                                    dockerClient.deployStack params.stagingEnvironment, params.stackName, env.version
                                }
                                jira.startManualVerification()
                            }
                        }
                        post {
                            failure {
                                script {
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    maven.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                            aborted {
                                script {
                                    dockerClient.deletePublished params.stagingEnvironment, env.version
                                    maven.deletePublished params.stagingEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                        }
                    }
                    stage('Wait for approval') {
                        steps {
                            script {
                                if (!jira.waitUntilManualVerificationIsStarted()) return
                                if (!jira.waitUntilManualVerificationIsFinishedAndAssertSuccess(env.sourceCodeRepository)) return
                                if (!jira.fixVersions().contains(env.version))
                                    env.verification = 'false'
                            }
                        }
                    }
                }
            }
            stage('Production deliver (Java)') {
                when { expression { env.verification == 'true' && params.productionEnvironment != null } }
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
                        git.checkoutVerificationBranch()
                        maven.deliver(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy, params.productionEnvironment)
                    }
                }
                post {
                    failure {
                        script {
                            maven.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                    aborted {
                        script {
                            maven.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                }
            }
            stage('Production deliver (Docker)') {
                when { expression { env.verification == 'true' && params.productionEnvironment != null } }
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
                            maven.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                    aborted {
                        script {
                            dockerClient.deletePublished params.productionEnvironment, env.version
                            maven.deletePublished params.productionEnvironment, env.version
                            git.deleteWorkBranch(params.gitSshKey)
                        }
                    }
                }
            }
            stage('Production') {
                options {
                    lock resource: productionLock
                }
                when { expression { env.verification == 'true' && params.productionEnvironment != null } }
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
                                if (params.productionEnvironmentType == 'puppet') {
                                    puppet.deploy params.productionEnvironment, env.version, params.puppetModules, params.librarianModules, params.puppetApplyList
                                } else if (params.productionEnvironmentType == 'docker') {
                                    dockerClient.deployStack params.productionEnvironment, params.stackName, env.version
                                }
                            }
                        }
                        post {
                            failure {
                                script {
                                    dockerClient.deletePublished params.productionEnvironment, env.version
                                    maven.deletePublished params.productionEnvironment, env.version
                                    git.deleteWorkBranch(params.gitSshKey)
                                }
                            }
                            aborted {
                                script {
                                    dockerClient.deletePublished params.productionEnvironment, env.version
                                    maven.deletePublished params.productionEnvironment, env.version
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
                        image agentImage
                        args agentArgs
                    }
                }
                steps {
                    script {
                        failIfJobIsAborted()
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
