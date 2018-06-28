package no.difi.jenkins.pipeline

import no.difi.jenkins.pipeline.stages.CheckBuild
import no.difi.jenkins.pipeline.stages.IntegrateCode
import no.difi.jenkins.pipeline.stages.PrepareVerification
import no.difi.jenkins.pipeline.stages.ProductionDeliverDocker
import no.difi.jenkins.pipeline.stages.ProductionDeliverJava
import no.difi.jenkins.pipeline.stages.ProductionDeploy
import no.difi.jenkins.pipeline.stages.StagingDeliverDocker
import no.difi.jenkins.pipeline.stages.StagingDeliverJava
import no.difi.jenkins.pipeline.stages.StagingDeploy
import no.difi.jenkins.pipeline.stages.VerificationDeliverDocker
import no.difi.jenkins.pipeline.stages.VerificationDeliverJava
import no.difi.jenkins.pipeline.stages.VerificationDeploy
import no.difi.jenkins.pipeline.stages.VerificationTests
import no.difi.jenkins.pipeline.stages.WaitForApproval
import no.difi.jenkins.pipeline.stages.WaitForCodeReviewToFinish
import no.difi.jenkins.pipeline.stages.WaitForVerificationToStart
@Grab('org.yaml:snakeyaml:1.19')
import org.yaml.snakeyaml.Yaml

class Components {

    Pipeline pipeline
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
    PrepareVerification prepareVerification
    VerificationDeliverJava verificationDeliverJava
    VerificationDeliverDocker verificationDeliverDocker
    VerificationDeploy verificationDeploy
    VerificationTests verificationTests
    WaitForCodeReviewToFinish waitForCodeReviewToFinish
    StagingDeliverJava stagingDeliverJava
    StagingDeliverDocker stagingDeliverDocker
    StagingDeploy stagingDeploy
    IntegrateCode integrateCode
    WaitForApproval waitForApproval
    ProductionDeliverJava productionDeliverJava
    ProductionDeliverDocker productionDeliverDocker
    ProductionDeploy productionDeploy

    Components(def jobName) {
        File configFile = "/config.yaml" as File
        Map config = new Yaml().load(configFile.text)
        File jobsFile = "/jobs.yaml" as File
        Map jobs = new Yaml().load(jobsFile.text).jobs
        pipeline = new Pipeline()
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
        git.sshKey = jobs[jobName].sshKey
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
        waitForCodeReviewToFinish = new WaitForCodeReviewToFinish()
        waitForCodeReviewToFinish.jira = jira
        waitForCodeReviewToFinish.git = git
        stagingDeliverJava = new StagingDeliverJava()
        stagingDeliverJava.jira = jira
        stagingDeliverJava.git = git
        stagingDeliverJava.maven = maven
        stagingDeliverDocker = new StagingDeliverDocker()
        stagingDeliverDocker.jira = jira
        stagingDeliverDocker.git = git
        stagingDeliverDocker.dockerClient = docker
        stagingDeliverDocker.maven = maven
        stagingDeploy = new StagingDeploy()
        stagingDeploy.git = git
        stagingDeploy.jira = jira
        stagingDeploy.puppet = puppet
        stagingDeploy.dockerClient = docker
        stagingDeploy.maven = maven
        integrateCode = new IntegrateCode()
        integrateCode.jira = jira
        integrateCode.git = git
        integrateCode.maven = maven
        integrateCode.dockerClient = docker
        waitForApproval = new WaitForApproval()
        waitForApproval.jira = jira
        waitForApproval.git = git
        productionDeliverJava = new ProductionDeliverJava()
        productionDeliverJava.jira = jira
        productionDeliverJava.git = git
        productionDeliverJava.maven = maven
        productionDeliverDocker = new ProductionDeliverDocker()
        productionDeliverDocker.jira = jira
        productionDeliverDocker.git = git
        productionDeliverDocker.dockerClient = docker
        productionDeliverDocker.maven = maven
        productionDeploy = new ProductionDeploy()
        productionDeploy.git = git
        productionDeploy.jira = jira
        productionDeploy.puppet = puppet
        productionDeploy.dockerClient = docker
        productionDeploy.maven = maven
    }

}
