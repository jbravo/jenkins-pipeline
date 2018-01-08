import no.difi.jenkins.pipeline.AWS
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.Puppet

def call(body) {
    AWS aws = new AWS()
    Docker dockerClient = new Docker()
    Git git = new Git()
    Maven maven = new Maven()
    Map params= [:]
    params.parallelMavenDeploy = true
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
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                             '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
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
                        maven.verify params.MAVEN_OPTS
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
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
                environment {
                    dockerHub = credentials('dockerHub')
                    nexus = credentials('nexus')
                }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                             '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        maven.deployDockerAndJava(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy,
                                'docker.io', env.dockerHub_USR, env.dockerHub_PSW,
                                'http://nexus:8081/repository/maven-releases', env.nexus_USR, env.nexus_PSW
                        )
                    }
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
            stage('Build Docker images') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        if (dockerClient.buildSupported()) {
                            dockerClient.buildAndPublish env.version, params.dockerRegistry
                        }
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        if (aws.infrastructureSupported()) {
                            String verificationHostName = aws.createInfrastructure env.version, params.verificationHostSshKey, env.aws_USR, env.aws_PSW, params.stackName
                            dockerClient.createStack params.verificationHostSshKey, "ubuntu", verificationHostName, params.stackName, env.version
                        } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                            env.stackName = new Random().nextLong().abs()
                            dockerClient.createStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName, env.version
                        }
                    }
                }
                post {
                    failure {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                        script {
                            git.deleteVerificationBranch(params.gitSshKey)
                            if (aws.infrastructureSupported()) {
                                aws.removeInfrastructure env.version, params.stackName
                            } else if (dockerClient.automaticVerificationSupported(params.verificationHostName)) {
                                dockerClient.removeStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                            }
                            dockerClient.deletePublished env.version, params.dockerRegistry
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
                            dockerClient.deletePublished env.version, params.dockerRegistry
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
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                             '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        if (maven.systemTestsSupported())
                            maven.runSystemTests params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, env.stackName
                        // TODO: Support the system tests for poc-statistics
                        //node1 = "statistics-${env.version}-node1"
                        //node2 = "statistics-${env.version}-node2"
                        //sh "pipelinex/environment.sh login ${node1} bash -s -- < pipelinex/application.sh verify ${env.version}"
                        //sh "pipelinex/environment.sh terminateNode ${node1}"
                        //sh "pipelinex/environment.sh login ${node2} bash -s -- < pipelinex/application.sh verifyTestData"
                    }
                }
                post {
                    always {
                        script {
                            if (maven.systemTestsSupported()) {
                                cucumber 'system-tests/target/cucumber-report.json'
                                jiraAddComment(
                                        idOrKey: issueId(),
                                        comment: "Verifikasjonstester utført: [Rapport|${env.BUILD_URL}cucumber-html-reports/overview-features.html] og [byggstatus|${env.BUILD_URL}]",
                                        auditLog: false
                                )
                            }
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
            stage('Publish Java artifacts') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                             '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                environment {
                    dockerHub = credentials('dockerHub')
                    artifactory = credentials('artifactory-publish')
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        git.checkoutVerificationBranch()
                        maven.deployDockerAndJava(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy,
                                'docker.io', env.dockerHub_USR, env.dockerHub_PSW,
                                'http://eid-artifactory.dmz.local:8080/artifactory/libs-release-local', env.artifactory_USR, env.artifactory_PSW
                        )
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
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
                            dockerClient.deletePublished env.version, params.dockerRegistry
                            maven.deletePublished env.version
                        }
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                    }
                    aborted {
                        transitionIssue env.ISSUE_STATUS_CODE_REVIEW, env.ISSUE_TRANSITION_RESUME_WORK
                        script {
                            dockerClient.deletePublished env.version, params.dockerRegistry
                            maven.deletePublished env.version
                        }
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        if (params.puppetModules != null) {
                            new Puppet().deploy env.version, params.verificationHostSshKey, params.puppetModules, params.librarianModules, params.puppetApplyList
                        } else if (dockerClient.stackSupported() && params.verificationHostName != null) {
                            dockerClient.createStack params.verificationHostSshKey, params.verificationHostUser, params.verificationHostName, params.stackName, env.version
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
                             '-v /var/jenkins_home/.ssh/known_hosts:/root/.ssh/known_hosts ' +
                             '-u root:root'
                    }
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        if (dockerClient.stackSupported() && params.productionHostName != null) {
                            dockerClient.createStack params.productionHostSshKey, params.productionHostUser, params.productionHostName, params.stackName, env.version
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
