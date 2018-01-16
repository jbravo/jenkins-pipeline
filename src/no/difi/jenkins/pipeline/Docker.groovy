package no.difi.jenkins.pipeline

import static java.util.Collections.emptyList

void deployStack(def sshKey, def user, def host, def registry, def stackName, def version) {
    String dockerHostFile = newDockerHostFile()
    String dockerHost = dockerHost dockerHostFile
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    if (fileExists("${WORKSPACE}/docker/run")) {
        sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost} docker/run ${stackName} ${version}; rm ${dockerHostFile}"
    } else {
        if (fileExists("${WORKSPACE}/docker/run-advice")) {
            sh "${WORKSPACE}/docker/run-advice before"
        }
        String registryAddress = registryAddress registry
        sh """#!/usr/bin/env bash
        export DOCKER_TLS_VERIFY=
        export DOCKER_HOST=${dockerHost}
        export REGISTRY=${registryAddress}
        export VERSION=${version}
        rc=1
        docker stack deploy -c docker/stack.yml ${stackName} || exit 1
        for i in \$(seq 1 100); do
            docker stack services ${stackName} --format '{{.Name}}:{{.Replicas}}' | grep -vE ':([0-9]+)/\\1' || { rc=0; break; }
            sleep 5
        done
        rm ${dockerHostFile}
        exit \${rc}
        """
        if (fileExists("${WORKSPACE}/docker/run-advice")) {
            sh "${WORKSPACE}/docker/run-advice after"
        }
    }
}

void removeStack(def sshKey, def user, def host, def stackName) {
    String dockerHostFile = newDockerHostFile()
    setupSshTunnel(sshKey, dockerHostFile, user, host)
    sh "DOCKER_TLS_VERIFY= DOCKER_HOST=${dockerHost(dockerHostFile)} docker stack rm ${stackName}; rm ${dockerHostFile}"
}

boolean stackSupported() {
    if (fileExists("${WORKSPACE}/docker/stack.yml")) {
        echo "Docker stack is supported"
        return true
    } else {
        echo "Docker stack is not supported"
        return false
    }
}

boolean automaticVerificationSupported(def verificationHostName) {
    stackSupported() && verificationHostName?.equals('eid-test01.dmz.local')
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
    sh "rm ${dockerHostFile}"
    return portMap
}

void buildAndPublish(def version, def registry) {
    withCredentials([usernamePassword(
            credentialsId: credentialsId(registry),
            passwordVariable: 'registryPassword',
            usernameVariable: 'registryUsername')]
    ) {

        String registryAddress = registryAddress registry
        if (fileExists("${WORKSPACE}/docker/build-images")) {
            echo "Using project specific script to build images"
            sh "docker/build-images ${registryAddress} ${version}"
            pushAll registryAddress, version, env.registryUsername, env.registryPassword
            removeAll(registryAddress, version)
        } else if (fileExists("${WORKSPACE}/docker/build")) {
            echo "Using legacy script to build images -- no staging support for images"
            sh "docker/build deliver ${version} ${env.registryUsername} ${env.registryPassword}"
        } else {
            buildAll(registryAddress, version)
            pushAll(registryAddress, version, env.registryUsername, env.registryPassword)
            removeAll(registryAddress, version)
        }
    }
}

void deletePublished(def version, def registry) {
    echo "Deleting published Docker images with version ${version} from registry ${registry}..."
    withCredentials([usernamePassword(
            credentialsId: credentialsId(registry),
            passwordVariable: 'registryPassword',
            usernameVariable: 'registryUsername')]
    ) {
        String registryApiUrl = registryApiUrl registry
        if (registryApiUrl == null || registryApiUrl.trim().isEmpty()) {
            echo "Registry ${registry} not supported for deletion"
            return
        }
        imageNames().each { imageName ->
            echo "Deleting image ${imageName}"
            sh returnStatus: true, script: """
                digest=\$(curl -sSf -o /dev/null -D - -u '${env.registryUsername}:${env.registryPassword}' -H 'Accept:application/vnd.docker.distribution.manifest.v2+json' ${registryApiUrl}/v2/${imageName}/manifests/${version} | grep Docker-Content-Digest | cut -d' ' -f2)
                digest=\${digest%[\$'\t\r\n']}
                curl -sSf -u '${env.registryUsername}:${env.registryPassword}' -X DELETE ${registryApiUrl}/v2/${imageName}/manifests/\${digest}
            """
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
    buildAll("verify", System.currentTimeMillis())
}

void buildAll(String registry, def tag) {
    if (fileExists("${WORKSPACE}/docker/build-advice")) {
        sh "${WORKSPACE}/docker/build-advice before"
    }
    imageNames().each { imageName ->
        buildImage(registry, imageName, tag)
    }
    if (fileExists("${WORKSPACE}/docker/build-advice")) {
        sh "${WORKSPACE}/docker/build-advice after"
    }
}

private boolean fileExists(String file) {
    0 == sh(returnStatus: true, script: "[ -e ${file} ]")
}

void pushAll(String registry, def tag, def username, def password) {
    imageNames().each { imageName ->
        push(registry, imageName, tag, username, password)
    }
}

void removeAll(String registry, def tag) {
    imageNames().each { imageName ->
        removeImage(registry, imageName, tag)
    }
}

void push(String registry, String imageName, def tag, def username, def password) {
    String pushRegistry = registry
    String loginRegistry = registry
    if (registry.startsWith('docker.io/')) {
        pushRegistry = registry.substring(10)
        loginRegistry = ""
    }
    sh """#!/usr/bin/env bash
    echo "Logging in to registry ${registry}..."
    echo "${password}" | docker login ${loginRegistry} -u "${username}" --password-stdin || { >&2 echo "Failed to login to registry for pushing image ${imageName}"; exit 1; }
    echo "Pushing image ${pushRegistry}/${imageName}:${tag}..."
    docker push ${pushRegistry}/${imageName}:${tag} || { >&2 echo "Failed to push tag '${tag}' image ${imageName}"; exit 1; }
    echo "Tagging image ${pushRegistry}/${imageName}:${tag} with tag 'latest'..."
    docker tag ${pushRegistry}/${imageName}:${tag} ${pushRegistry}/${imageName}
    echo "Pushing image ${pushRegistry}/${imageName}:latest..."
    docker push ${pushRegistry}/${imageName} || { >&2 echo "Failed to push tag 'latest' for image ${imageName}"; exit 1; }
    echo "Logging out from registry ${registry}..."
    docker logout ${loginRegistry}; exit 0
    """
}

void buildImage(String registry, String imageName, def tag) {
    if (registry.startsWith('docker.io/'))
        registry = registry.substring(10)
    sh "docker build -t ${registry}/${imageName}:${tag} ${WORKSPACE}/docker/${imageName}"
}

void removeImage(String registry, String imageName, def tag) {
    if (registry.startsWith('docker.io/'))
        registry = registry.substring(10)
    sh "docker rmi ${registry}/${imageName}:${tag}"
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

String credentialsId(def registry) {
    registry = backwardsCompatible(registry)
    "docker_registry_${registry}"
}

String registryAddress(def registry) {
    echo "Looking up registry address for: ${registry}"
    registry = backwardsCompatible(registry)
    switch(registry) {
        case 'StagingLocal':
            return "${env.dockerRegistryStagingLocalAddress}"
        case 'StagingPublic':
            return "${env.dockerRegistryStagingPublicAddress}"
        case 'ProductionLocal':
            return "${env.dockerRegistryProductionLocalAddress}"
        case 'ProductionPublic':
            return "${env.dockerRegistryProductionPublicAddress}"
        default:
            return null
    }
}

private String registryApiUrl(def registry) {
    echo "Looking up registry API URL for: ${registry}"
    registry = backwardsCompatible(registry)
    switch(registry) {
        case 'StagingLocal':
            return "${env.dockerRegistryStagingLocalApiUrl}"
        case 'StagingPublic':
            return "${env.dockerRegistryStagingPublicApiUrl}"
        case 'ProductionLocal':
            return "${env.dockerRegistryProductionLocalApiUrl}"
        case 'ProductionPublic':
            return "${env.dockerRegistryProductionPublicApiUrl}"
        default:
            return null
    }
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
