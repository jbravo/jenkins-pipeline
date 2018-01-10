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

boolean automaticVerificationSupported(def verificationHostName) {
    stackSupported() && verificationHostName?.equals('eid-test01.dmz.local')
}

boolean buildSupported() {
    if (!new File("${env.WORKSPACE}/docker/build").exists()) {
        echo "Project has no docker/build script"
        return false
    }
    return true
}

boolean serviceExists(def stackName, String service, String dockerHost) {
    0 == sh(returnStatus: true, script: "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost} docker service inspect ${stackName}_${service} > /dev/null")
}

Map servicePorts(def sshKey, def user, def host, def stackName, String...services) {
    String dockerHostFile = newDockerHostFile()
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    String dockerHost = dockerHost dockerHostFile
    def portMap = new HashMap()
    services.each { service ->
        if (serviceExists(stackName, service, dockerHost)) {
            String port = sh(returnStdout: true, script: "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost} docker service inspect --format='{{with index .Endpoint.Ports 0}}{{.PublishedPort}}{{end}}' ${stackName}_${service}").trim()
            portMap.put(service, port)
        }
    }
    new File(dockerHostFile).delete()
    return portMap
}

void buildAndPublish(def version, def dockerRegistry) {
    withCredentials([usernamePassword(
            credentialsId: dockerRegistry,
            passwordVariable: 'dockerRegistryPassword',
            usernameVariable: 'dockerRegistryUsername')]
    ) {
        sh "docker/build deliver ${version} ${env.dockerRegistryUsername} ${env.dockerRegistryPassword}"
    }
}

void deletePublished(def version, def registry) {
    withCredentials([usernamePassword(
            credentialsId: registry,
            passwordVariable: 'password',
            usernameVariable: 'username')]
    ) {
        echo "Deleting published Docker images..."
        new File("${WORKSPACE}/docker").eachDir { dir ->
            String imageName = dir.getName()
            echo "Deleting image ${imageName}"
            String registryUrl = registryUrl registry
            if (registryUrl == null) {
                echo "Registry ${registry} not supported for deletion"
                return
            }
            sh returnStatus: true, script: """
              digest=\$(curl -sSf -o /dev/null -D - -u '${env.username}:${env.password}' -H 'Accept:application/vnd.docker.distribution.manifest.v2+json' ${registryUrl}/repository/docker/v2/${imageName}/manifests/${version} | grep Docker-Content-Digest | cut -d' ' -f2)
              curl -sSf -u '${env.username}:${env.password}' -X DELETE ${registryUrl}/repository/docker/v2/${imageName}/manifests/\${digest}
            """
        }
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

private static String registryUrl(def registry) {
    switch (registry) {
        case 'nexus': return 'http://eid-nexus01.dmz.local:8080'
        default: return null
    }
}
