package no.difi.jenkins.pipeline

def importSshKey(String name, String publicKeyMaterial) {
    sh "aws ec2 import-key-pair --key-name ${name} --public-key-material \"${publicKeyMaterial}\""
}

def createInfrastructure(def version, def sshKey, def awsKeyId, def awsKey, def stackName) {
    env.AWS_DEFAULT_REGION = 'us-east-1'
    env.AWS_ACCESS_KEY_ID = awsKeyId
    env.AWS_SECRET_ACCESS_KEY = awsKey
    String systemId = "${stackName}-${version}"
    sshagent([sshKey]) {
        String sshPublicKey = sh returnStdout: true, script: 'ssh-add -L'
        importSshKey systemId, sshPublicKey
        sh "docker/infrastructure createVpc ${systemId}"
        sh "docker/infrastructure createInternetGateway ${systemId}"
        sh "docker/infrastructure createRoute ${systemId}"
        sh "docker/infrastructure createSecurityGroup ${systemId}"
        sh "docker/infrastructure createDockerMachinesOnAws ${systemId}"
        sh "docker/infrastructure setupDockerSwarm ${systemId}"
    }
    String user = 'ubuntu'
    sh returnStdout: true, script: "source .environment/${systemId}-node01 2>/dev/null && echo \${address}"
}

def removeInfrastructure(def version, def stackName) {
    if (!infrastructureSupported()) return
    String systemId = "${stackName}-${version}"
    sh "docker/infrastructure delete ${systemId}"
}

boolean infrastructureSupported() {
    int status = sh(returnStatus: true, script: "[ -e ${WORKSPACE}/docker/infrastructure ]")
    if (status == 0) {
        echo "Infrastructure supported"
        return true
    } else {
        echo "Infrastructure not supported"
        return false
    }
}