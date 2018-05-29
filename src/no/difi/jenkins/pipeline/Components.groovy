package no.difi.jenkins.pipeline

import no.difi.jenkins.pipeline.stages.CheckBuild
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
    }

}
