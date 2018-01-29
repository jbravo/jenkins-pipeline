import no.difi.jenkins.pipeline.Components
import no.difi.jenkins.pipeline.Jira
import no.difi.jenkins.pipeline.Docker
import no.difi.jenkins.pipeline.Git
import no.difi.jenkins.pipeline.Maven
import no.difi.jenkins.pipeline.Puppet

def call(body) {
    Components components = new Components()
    Jira jira = components.jira
    Docker dockerClient = components.docker
    Git git = components.git
    Maven maven = components.maven
    Puppet puppet = components.puppet
    String projectName = JOB_NAME.tokenize('/')[0]
    Map params = [:]
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
                        String commitMessage = git.readCommitMessage()
                        if (commitMessage.startsWith('ready!'))
                            env.verification = 'true'
                        if (commitMessage.startsWith('ready!!'))
                            env.startVerification = 'true'
                        maven.verify params.MAVEN_OPTS
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
            stage('Verification deliver (Java)') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.verificationEnvironment != null } }
                environment {
                    nexus = credentials('nexus')
                }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                                '--network pipeline_pipeline ' +
                                '-v /var/run/docker.sock:/var/run/docker.sock ' +
                                '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                                '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        maven.deployDockerAndJava(env.version, params.MAVEN_OPTS, params.parallelMavenDeploy,
                                params.verificationEnvironment,
                                'http://nexus:8081/repository/maven-releases', env.nexus_USR, env.nexus_PSW
                        )
                    }
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
            stage('Verification deliver (Docker)') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.verificationEnvironment != null } }
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.verificationEnvironment != null } }
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.verificationEnvironment != null } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                             '--network pipeline_pipeline ' +
                             '-v /var/run/docker.sock:/var/run/docker.sock ' +
                             '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                             '-u root:root'
                    }
                }
                steps {
                    script {
                        git.checkoutVerificationBranch()
                        if (maven.systemTestsSupported())
                            maven.runSystemTests params.verificationEnvironment, env.stackName
                    }
                }
                post {
                    always {
                        script {
                            if (maven.systemTestsSupported()) {
                                cucumber 'system-tests/target/cucumber-report.json'
                                jira.addComment(
                                        "Verifikasjonstester utført: [Rapport|${env.BUILD_URL}cucumber-html-reports/overview-features.html] og [byggstatus|${env.BUILD_URL}]",
                                )
                            }
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
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
            stage('Staging/production deliver (Java)') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
                agent {
                    docker {
                        label 'slave'
                        image 'difi/jenkins-agent'
                        args '--mount type=volume,src=pipeline-maven-repo-cache,dst=/root/.m2/repository ' +
                                '--network pipeline_pipeline ' +
                                '-v /var/run/docker.sock:/var/run/docker.sock ' +
                                '--mount type=volume,src=jenkins-ssh-settings,dst=/etc/ssh ' +
                                '-u root:root'
                    }
                }
                environment {
                    artifactory = credentials('artifactory-publish')
                }
                steps {
                    failIfJobIsAborted()
                    script {
                        git.checkoutVerificationBranch()
                        String javaRepository = 'http://eid-artifactory.dmz.local:8080/artifactory/libs-release-local'
                        if (params.stagingEnvironment != null) {
                            maven.deployDockerAndJava(
                                    env.version, params.MAVEN_OPTS, params.parallelMavenDeploy,
                                    params.stagingEnvironment,
                                    javaRepository, env.artifactory_USR, env.artifactory_PSW
                            )
                        } else {
                            maven.deployJava(
                                    env.version, params.MAVEN_OPTS, params.parallelMavenDeploy,
                                    javaRepository, env.artifactory_USR, env.artifactory_PSW
                            )
                        }
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
            stage('Staging deliver (Docker)') {
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.stagingEnvironment != null } }
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
                        dockerClient.buildAndPublish params.stagingEnvironment, env.version
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
            stage('Staging') {
                options {
                    lock resource: projectName
                }
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.stagingEnvironment != null } }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "${params.stagingEnvironmentType}:${params.stagingEnvironment}"
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.productionEnvironment != null } }
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' && params.productionEnvironment != null } }
                failFast true
                parallel() {
                    stage('Deploy') {
                        options {
                            lock resource: "${params.productionEnvironmentType}:${params.productionEnvironment}"
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
                when { expression { env.BRANCH_NAME.matches(/work\/(\w+-\w+)/) && env.verification == 'true' } }
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
