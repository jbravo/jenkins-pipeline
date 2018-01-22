package no.difi.jenkins.pipeline

@Grab('org.yaml:snakeyaml:1.19')
import org.yaml.snakeyaml.Yaml

class Components {

    ErrorHandler errorHandler
    AWS aws
    Crucible crucible
    Docker docker
    Git git
    Jira jira
    Maven maven
    Puppet puppet

    Components() {
        File configFile = "/config.yaml" as File
        Map config = new Yaml().load(configFile.text)
        errorHandler = new ErrorHandler()
        aws = new AWS()
        crucible = new Crucible()
        docker = new Docker()
        git = new Git()
        jira = new Jira()
        jira.config = config.jira
        jira.errorHandler = errorHandler
        maven = new Maven()
        puppet = new Puppet()
    }

}
