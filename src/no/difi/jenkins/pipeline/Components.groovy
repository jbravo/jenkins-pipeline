package no.difi.jenkins.pipeline

import no.difi.jenkins.pipeline.stages.CheckBuild
import no.difi.jenkins.pipeline.stages.IntegrateCode
import no.difi.jenkins.pipeline.stages.PrepareVerification
import no.difi.jenkins.pipeline.stages.StagingDeliverDocker
import no.difi.jenkins.pipeline.stages.StagingDeliverJava
import no.difi.jenkins.pipeline.stages.VerificationDeliverDocker
import no.difi.jenkins.pipeline.stages.VerificationDeliverJava
import no.difi.jenkins.pipeline.stages.VerificationDeploy
import no.difi.jenkins.pipeline.stages.VerificationTests
import no.difi.jenkins.pipeline.stages.WaitForVerificationSlot
import no.difi.jenkins.pipeline.stages.WaitForVerificationToStart
@Grab('org.yaml:snakeyaml:1.19')
import org.yaml.snakeyaml.Yaml

class Components {

    ErrorHandler errorHandler
    Environments environments
    AWS aws
    Crucible crucible
    Docker docker
    Git git
    Jira jira
    Maven maven
    Puppet puppet
    CheckBuild checkBuild
    WaitForVerificationToStart waitForVerificationToStart
    WaitForVerificationSlot waitForVerificationSlot
    PrepareVerification prepareVerification
    VerificationDeliverJava verificationDeliverJava
    VerificationDeliverDocker verificationDeliverDocker
    VerificationDeploy verificationDeploy
    VerificationTests verificationTests
    StagingDeliverJava stagingDeliverJava
    StagingDeliverDocker stagingDeliverDocker
    IntegrateCode integrateCode

    Components() {
        File configFile = "/config.yaml" as File
        Map config = new Yaml().load(configFile.text)
        errorHandler = new ErrorHandler()
        environments = new Environments()
        environments.config = config
        aws = new AWS()
        crucible = new Crucible()
        docker = new Docker()
        docker.config = config.docker
        docker.environments = environments
        git = new Git()
        git.errorHandler = errorHandler
        jira = new Jira()
        jira.config = config.jira
        jira.errorHandler = errorHandler
        maven = new Maven()
        maven.environments = environments
        maven.docker = docker
        maven.errorHandler = errorHandler
        puppet = new Puppet()
        puppet.environments = environments
        checkBuild = new CheckBuild()
        checkBuild.errorHandler = errorHandler
        checkBuild.git = git
        checkBuild.jira = jira
        checkBuild.dockerClient = docker
        checkBuild.maven = maven
        waitForVerificationToStart = new WaitForVerificationToStart()
        waitForVerificationToStart.jira = jira
        waitForVerificationSlot = new WaitForVerificationSlot()
        waitForVerificationSlot.git = git
        waitForVerificationSlot.jira = jira
        waitForVerificationSlot.errorHandler = errorHandler
        prepareVerification = new PrepareVerification()
        prepareVerification.git = git
        prepareVerification.jira = jira
        prepareVerification.crucible = crucible
        verificationDeliverJava = new VerificationDeliverJava()
        verificationDeliverJava.jira = jira
        verificationDeliverJava.git = git
        verificationDeliverJava.maven = maven
        verificationDeliverDocker = new VerificationDeliverDocker()
        verificationDeliverDocker.jira = jira
        verificationDeliverDocker.git = git
        verificationDeliverDocker.dockerClient = docker
        verificationDeliverDocker.maven = maven
        verificationDeploy = new VerificationDeploy()
        verificationDeploy.jira = jira
        verificationDeploy.git = git
        verificationDeploy.dockerClient = docker
        verificationDeploy.maven = maven
        verificationTests = new VerificationTests()
        verificationTests.jira = jira
        verificationTests.git = git
        verificationTests.dockerClient = docker
        verificationTests.maven = maven
        stagingDeliverJava = new StagingDeliverJava()
        stagingDeliverJava.jira = jira
        stagingDeliverJava.git = git
        stagingDeliverJava.maven = maven
        stagingDeliverJava.errorHandler = errorHandler
        stagingDeliverDocker = new StagingDeliverDocker()
        stagingDeliverDocker.jira = jira
        stagingDeliverDocker.git = git
        stagingDeliverDocker.dockerClient = docker
        stagingDeliverDocker.maven = maven
        stagingDeliverDocker.errorHandler = errorHandler
        integrateCode = new IntegrateCode()
        integrateCode.jira = jira
        integrateCode.git = git
        integrateCode.maven = maven
        integrateCode.dockerClient = docker
        integrateCode.errorHandler = errorHandler
    }

}
