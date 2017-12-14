package no.difi.jenkins.pipeline

void createStack(def sshKey, def user, def host, def stackName, def version) {
    String dockerHostFile = newDockerHostFile()
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost(dockerHostFile)} docker/run ${stackName} ${version}"
    new File(dockerHostFile).delete()
}

void removeStack(def sshKey, def user, def host, def stackName) {
    String dockerHostFile = newDockerHostFile()
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost(dockerHostFile)} docker stack rm ${stackName}"
    new File(dockerHostFile).delete()
}

boolean stackSupported() {
    if (!new File("${env.WORKSPACE}/docker/stack.yml").exists()) {
        echo "Project has no Docker compose file (docker/stack.yml)"
        return false
    }
    return true
}

boolean buildSupported() {
    if (!new File("${env.WORKSPACE}/docker/build").exists()) {
        echo "Project has no docker/build script"
        return false
    }
    return true
}

Map servicePorts(def sshKey, def user, def host, def stackName, String...services) {
    String dockerHostFile = newDockerHostFile()
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    def portMap = new HashMap()
    services.each { service ->
        String port = sh(returnStdout: true, script: "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost(dockerHostFile)} docker service inspect --format='{{with index .Endpoint.Ports 0}}{{.PublishedPort}}{{end}}' ${stackName}_${service}").trim()
        portMap.put(service, port)
    }
    new File(dockerHostFile).delete()
    return portMap
}

void build(def version, def dockerRegistry) {
    withCredentials([usernamePassword(
            credentialsId: dockerRegistry,
            passwordVariable: 'dockerRegistryPassword',
            usernameVariable: 'dockerRegistryUsername')]
    ) {
        sh "docker/build deliver ${version} ${env.dockerRegistryUsername} ${env.dockerRegistryPassword}"
    }
}

void verify() {
    sh "docker/build verify"
}

private void setupSshTunnel(def sshKey, def dockerHostFile, def user, def host) {
    sshagent([sshKey]) {
        sh "ssh -f -o ExitOnForwardFailure=yes -L ${dockerHostFile}:/var/run/docker.sock ${user}@${host} sleep 3600"
    }
}

private static String newDockerHostFile() {
    "/tmp/docker${new Random().nextInt(1000000)}.sock"
}

private static String dockerHost(String dockerHostFile) {
    "unix://${dockerHostFile}"
}