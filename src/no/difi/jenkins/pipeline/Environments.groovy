package no.difi.jenkins.pipeline

class Environments {

    Map config

    boolean isDockerDeliverySupported(String environmentId) {
        dockerRegistryId(environmentId) != null
    }

    boolean isDockerDeploySupported(String environmentId) {
        dockerSwarmId(environmentId) != null
    }

    String dockerRegistryCredentialsId(String environmentId) {
        dockerRegistry(dockerRegistryId(environmentId)).credentialsId
    }

    String dockerRegistryAddress(String environmentId) {
        dockerRegistry(dockerRegistryId(environmentId)).address
    }

    String dockerRegistryApiUrl(String environmentId) {
        dockerRegistry(dockerRegistryId(environmentId)).apiUrl
    }

    String dockerSwarmHost(String environmentId) {
        dockerSwarm(dockerSwarmId(environmentId)).host
    }

    String dockerSwarmUser(String environmentId) {
        dockerSwarm(dockerSwarmId(environmentId)).user
    }

    String dockerSwarmSshKey(String environmentId) {
        dockerSwarm(dockerSwarmId(environmentId)).sshKey
    }
    String dockerOndemandServices(String environmentId) {
        config.docker.ondemand[dockerSwarm(dockerSwarmId(environmentId)).ondemand] as Map
    }

    boolean isMavenDeletionSupported(String environmentId) {
        mavenRepository(mavenRepositoryId(environmentId)).address.startsWith('http://eid-artifactory.dmz.local:8080')
    }

    String mavenRepositoryCredentialsId(String environmentId) {
        mavenRepository(mavenRepositoryId(environmentId)).credentialsId
    }

    String mavenRepositoryAddress(String environmentId) {
        mavenRepository(mavenRepositoryId(environmentId)).address
    }

    String puppetMasterSshKey(String environmentId) {
        puppetMaster(puppetMasterId(environmentId)).sshKey
    }

    String puppetMasterHost(String environmentId) {
        puppetMaster(puppetMasterId(environmentId)).host
    }

    String puppetMasterUser(String environmentId) {
        puppetMaster(puppetMasterId(environmentId)).user
    }

    private Map environment(String environmentId) {
        config.environments[environmentId] as Map
    }

    private Map dockerRegistry(String registryId) {
        config.docker.registries[registryId] as Map
    }

    private String dockerRegistryId(String environmentId) {
        if (environment(environmentId).dockerRegistry != null) {
            environment(environmentId).dockerRegistry
        } else if (environment(environmentId).dockerSwarm != null) {
            dockerSwarm(dockerSwarmId(environmentId)).registry
        } else
            null
    }

    private Map dockerSwarm(String swarmId) {
        config.docker.swarms[swarmId] as Map
    }

    private String dockerSwarmId(String environmentId) {
        environment(environmentId).dockerSwarm
    }

    private Map mavenRepository(String repositoryId) {
        config.maven.repositories[repositoryId] as Map
    }

    private String mavenRepositoryId(String environmentId) {
        environment(environmentId).mavenRepository
    }

    private Map puppetMaster(String puppetMasterId) {
        config.puppet.masters[puppetMasterId] as Map
    }

    private String puppetMasterId(String environmentId) {
        environment(environmentId).puppetMaster
    }

}
