package no.difi.jenkins.pipeline

import static java.util.Collections.emptyList

Map config
Environments environments

static String uniqueStackName() {
    new Random().nextLong().abs()
}

String deployStack(def environmentId, def stackName, def version) {
    if (!environments.isDockerDeploySupported(environmentId)) {
        echo "Deploy of Docker stack for environment ${environmentId} is not supported"
        return null
    }
    String dockerHostFile = newDockerHostFile()
    String dockerHost = dockerHost dockerHostFile
    String sshKey = environments.dockerSwarmSshKey(environmentId)
    String user = environments.dockerSwarmUser(environmentId)
    String host = environments.dockerSwarmHost(environmentId)
    String registryAddress = environments.dockerRegistryAddress(environmentId)
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    if (fileExists("${WORKSPACE}/docker/run")) {
        sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost} docker/run ${stackName} ${version}; rm ${dockerHostFile}"
    } else if (fileExists("${WORKSPACE}/docker/stack.yml")) {
        if (fileExists("${WORKSPACE}/docker/run-advice")) {
            sh "${WORKSPACE}/docker/run-advice before ${stackName} ${host} ${version}"
        }
        sh """#!/usr/bin/env bash
        export DOCKER_TLS_VERIFY=
        export DOCKER_HOST=${dockerHost}
        export REGISTRY=${registryAddress}
        export VERSION=${version}
        rc=1
        docker stack deploy -c docker/stack.yml ${stackName} || exit 1
        for i in \$(seq 1 100); do
            output=\$(docker stack services ${stackName} --format '{{.Name}}:{{.Replicas}}') || { rc=1; break; }
            echo \${output} | grep -vE ':([0-9]+)/\\1' || { rc=0; break; }
            sleep 5
        done
        rm ${dockerHostFile}
        exit \${rc}
        """
        if (fileExists("${WORKSPACE}/docker/run-advice")) {
            sh "${WORKSPACE}/docker/run-advice after ${stackName} ${host}"
        }
    }
    stackName
}

void removeStack(def environmentId, def stackName) {
    if (!environments.isDockerDeploySupported(environmentId)) {
        echo "No Docker stack to remove"
        return
    }
    String sshKey = environments.dockerSwarmSshKey(environmentId)
    String user = environments.dockerSwarmUser(environmentId)
    String host = environments.dockerSwarmHost(environmentId)
    String dockerHostFile = newDockerHostFile()
    try {
        setupSshTunnel(sshKey, dockerHostFile, user, host)
        sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost(dockerHostFile)} docker stack rm ${stackName}; rm ${dockerHostFile}"
    } catch (e) {
        echo "Failed to remove stack ${stackName} from Docker swarm for environment ${environmentId}: ${e.message}"
    }
}

private boolean serviceExists(def stackName, String service, String dockerHost) {
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
    sh "rm ${dockerHostFile}"
    return portMap
}

void buildAndPublish(def environmentId, def version) {
    if (!environments.isDockerDeliverySupported(environmentId)) {
        echo "Delivery of Docker images is not supported for environment '${environmentId}'"
        return
    }
    if (imageNames().size() == 0)
        return
    String registryAddress = environments.dockerRegistryAddress(environmentId)
    String registryCredentialsId = environments.dockerRegistryCredentialsId(environmentId)
    if (fileExists("${WORKSPACE}/docker/build-images")) {
        echo "Using project specific script to build images"
        sh "docker/build-images ${registryAddress} ${version}"
        pushAll registryAddress, registryCredentialsId, version
        removeAll registryAddress, version
    } else if (fileExists("${WORKSPACE}/docker/build")) {
        echo "Using legacy script to build images -- no staging support for images"
        backwardsCompatibleBuildAndPublish(version)
    } else {
        buildAll registryAddress, version
        pushAll registryAddress, registryCredentialsId, version
        removeAll registryAddress, version
    }
}

void deletePublished(def environmentId, def version) {
    def credentialsId = environments.dockerRegistryCredentialsId(environmentId)
    if (credentialsId == null) {
        echo "No Docker registry credentials configured for environment '${environmentId} -- will not attempt to delete published images"
        return
    }
    String registryApiUrl = environments.dockerRegistryApiUrl(environmentId)
    if (registryApiUrl == null) {
        echo "No Docker registry API URL configured for environment '${environmentId} -- will not attempt to delete published images"
        return
    }
    echo "Deleting published Docker images with version ${version}..."
    withCredentials([usernamePassword(
            credentialsId: credentialsId,
            passwordVariable: 'registryPassword',
            usernameVariable: 'registryUsername')]
    ) {
        imageNames().each { imageName ->
            echo "Deleting image ${imageName}"
            int status = sh returnStatus: true, script: """
                digest=\$(curl -sSf -o /dev/null -D - -u '${env.registryUsername}:${env.registryPassword}' -H 'Accept:application/vnd.docker.distribution.manifest.v2+json' ${registryApiUrl}/v2/${imageName}/manifests/${version} | grep Docker-Content-Digest | cut -d' ' -f2)
                digest=\${digest%[\$'\t\r\n']}
                curl -sSf -u '${env.registryUsername}:${env.registryPassword}' -X DELETE ${registryApiUrl}/v2/${imageName}/manifests/\${digest}
            """
            if (status != 0) {
                echo "Failed to delete image ${imageName} from registry"
            }
        }
    }
}

private List<String> imageNames() {
    String result = sh(returnStdout: true, script: "[ -e ${WORKSPACE}/docker ] && find ${WORKSPACE}/docker -maxdepth 1 -mindepth 1 -type d -exec basename {} \\; || echo -n")
    if (result.trim().isEmpty())
        return emptyList()
    return result.split("\\s+")
}

void verify() {
    if (imageNames().size() == 0)
        return
    if (fileExists("${WORKSPACE}/docker/build-images")) {
        echo "Using project specific script to build images"
        sh "docker/build-images"
    } else if (fileExists("${WORKSPACE}/docker/build")) {
        echo "Using legacy script to build images"
        sh "docker/build verify"
    } else {
        buildAll null, null
    }
}

private void buildAll(String registryAddress, def tag) {
    if (fileExists("${WORKSPACE}/docker/build-advice")) {
        sh "${WORKSPACE}/docker/build-advice before"
    }
    imageNames().each { imageName ->
        buildImage(registryAddress, imageName, tag)
    }
    if (fileExists("${WORKSPACE}/docker/build-advice")) {
        sh "${WORKSPACE}/docker/build-advice after"
    }
}

private boolean fileExists(String file) {
    0 == sh(returnStatus: true, script: "[ -e ${file} ]")
}

private void pushAll(String registryAddress, def credentialsId, def tag) {
    withCredentials([usernamePassword(
            credentialsId: credentialsId,
            passwordVariable: 'password',
            usernameVariable: 'username')]
    ) {
        imageNames().each { imageName ->
            push(registryAddress, imageName, tag, env.username, env.password)
        }
    }
}

private void removeAll(String registryAddress, def tag) {
    imageNames().each { imageName ->
        removeImage(registryAddress, imageName, tag)
    }
}

private void push(String registryAddress, String imageName, def tag, def username, def password) {
    String pushAddress = registryAddress
    String loginAddress = registryAddress
    if (registryAddress.startsWith('docker.io/')) {
        pushAddress = registryAddress.substring(10)
        loginAddress = ""
    }
    sh """#!/usr/bin/env bash
    echo "Logging in to registry ${registryAddress}..."
    echo "${password}" | docker login ${loginAddress} -u "${username}" --password-stdin || { >&2 echo "Failed to login to registry for pushing image ${imageName}"; exit 1; }
    echo "Pushing image ${pushAddress}/${imageName}:${tag}..."
    docker push ${pushAddress}/${imageName}:${tag} || { >&2 echo "Failed to push tag '${tag}' image ${imageName}"; exit 1; }
    echo "Tagging image ${pushAddress}/${imageName}:${tag} with tag 'latest'..."
    docker tag ${pushAddress}/${imageName}:${tag} ${pushAddress}/${imageName}
    echo "Pushing image ${pushAddress}/${imageName}:latest..."
    docker push ${pushAddress}/${imageName} || { >&2 echo "Failed to push tag 'latest' for image ${imageName}"; exit 1; }
    echo "Logging out from registry ${registryAddress}..."
    docker logout ${loginAddress}; exit 0
    """
}

private void buildImage(String registryAddress, String imageName, def tag) {
    if (registryAddress?.startsWith('docker.io/'))
        registryAddress = registryAddress.substring(10)
    String fullName =
            "${registryAddress != null ? ("${registryAddress}/") : ""}${imageName}${tag != null ? ":${tag}" : ""}"
    sh "docker build -t ${fullName} ${WORKSPACE}/docker/${imageName}"
}

private void removeImage(String registryAddress, String imageName, def tag) {
    if (registryAddress.startsWith('docker.io/'))
        registryAddress = registryAddress.substring(10)
    sh "docker rmi ${registryAddress}/${imageName}:${tag}"
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

def credentialsId(def registry) {
    registry = backwardsCompatible(registry)
    config.registries[registry as String]?.credentialsId
}

private String backwardsCompatible(def registry) {
    switch(registry) {
        case 'nexus':
            echo 'Mapping nexus to ProductionLocal'
            return 'ProductionLocal'
        case 'dockerHub':
            echo 'Mapping dockerHub to ProductionPublic'
            return 'ProductionPublic'
        default:
            return registry
    }
}

private backwardsCompatibleBuildAndPublish(def version) {
    withCredentials([usernamePassword(
            credentialsId: credentialsId('ProductionLocal'),
            passwordVariable: 'registryPassword',
            usernameVariable: 'registryUsername')]
    ) {
        sh "docker/build deliver ${version} ${env.registryUsername} ${env.registryPassword}"
    }
}