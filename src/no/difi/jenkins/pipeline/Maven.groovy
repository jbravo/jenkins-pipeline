package no.difi.jenkins.pipeline

import groovy.json.JsonSlurperClassic
import groovy.text.SimpleTemplateEngine

Docker docker
Environments environments
ErrorHandler errorHandler

boolean isMavenProject() {
    0 == sh(returnStatus: true, script: "[ -e ${WORKSPACE}/pom.xml ]")
}

void verify(def options) {
    String settingsFile = settingsFile()
    env.MAVEN_OPTS = options ?: ""
    int status = sh returnStatus: true, script: """
        mvn clean verify -B -T 1C -s ${settingsFile}
    """
    sh "rm ${settingsFile}"
    try {
        junit '**/target/surefire-reports/TEST-*.xml'
    } catch (e) {
        echo "JUnit report was not created"
    }
    if (status != 0)
        errorHandler.trigger "Maven build failed (exit code ${status})"
}

void deliver(
        def version, def mavenOptions, def parallel, def environmentId
) {
    if (environments.isDockerDeliverySupported(environmentId)) {
        deliverDockerAndJava(version, mavenOptions, parallel, environmentId)
    } else {
        deliverJava(version, mavenOptions, parallel, environmentId)
    }
}

private void deliverDockerAndJava(
        def version, def mavenOptions, def parallel, def environmentId
) {
    withCredentials([usernamePassword(
            credentialsId: environments.dockerRegistryCredentialsId(environmentId),
            passwordVariable: 'dockerPassword',
            usernameVariable: 'dockerUsername')]
    ) {
        withCredentials([usernamePassword(
                credentialsId: environments.mavenRepositoryCredentialsId(environmentId),
                passwordVariable: 'mavenPassword',
                usernameVariable: 'mavenUsername')]
        ) {
            String settingsFile = settingsFileWithDockerAndJava(
                    environments.dockerRegistryAddress(environmentId),
                    env.dockerUsername,
                    env.dockerPassword,
                    "javaRepository",
                    env.mavenUsername,
                    env.mavenPassword
            )
            deploy(
                    version,
                    mavenOptions,
                    parallel,
                    environments.mavenRepositoryAddress(environmentId),
                    settingsFile
            )
        }
    }
}

private void deliverJava(
        def version, def mavenOptions, def parallel, def environmentId
) {
    withCredentials([usernamePassword(
            credentialsId: environments.mavenRepositoryCredentialsId(environmentId),
            passwordVariable: 'mavenPassword',
            usernameVariable: 'mavenUsername')]
    ) {
        String settingsFile = settingsFileWithJava(
                "javaRepository",
                env.mavenUsername,
                env.mavenPassword
        )
        deploy(
                version,
                mavenOptions,
                parallel,
                environments.mavenRepositoryAddress(environmentId),
                settingsFile
        )
    }
}

private void deploy(def version, def mavenOptions, def parallel, def javaRepository, String settingsFile) {
    env.MAVEN_OPTS = mavenOptions ?: ""
    String parallelOptions = parallel ? "-T 1C" : ""
    sh "mvn versions:set -B -DnewVersion=${version}"
    sh "mvn deploy -DdeployAtEnd=true -DaltDeploymentRepository=javaRepository::default::${javaRepository} -B ${parallelOptions} -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

boolean verificationTestsSupported(def environmentId) {
    if (!environments.isDockerDeploySupported(environmentId)) {
        echo "No Docker swarm defined for environment '${environmentId}' -- skipping tests"
        return false
    }
    int status = sh(returnStatus: true, script: "[ -e ${WORKSPACE}/system-tests ]")
    if (status != 0){
        echo "Verification tests are not supported (no system-tests folder)"
        return false
    }
    true
}

VerificationTestResult runVerificationTests(def environmentId, def stackName) {
    String sshKey = environments.dockerSwarmSshKey(environmentId)
    String user = environments.dockerSwarmUser(environmentId)
    String host = environments.dockerSwarmHost(environmentId)
    Map servicePorts = docker.servicePorts(
            sshKey, user, host, stackName,
            'eid-atest-admin:10001','eid-atest-admin:10006', 'eid-atest-idp-app:10001', 'selenium:4444', 'eid-atest-db:3306'
    )
    int status = sh returnStatus: true, script: """
        mvn verify -pl system-tests -PsystemTests -B\
        -DadminDirectBaseURL=http://${host}:${servicePorts.get('eid-atest-admin:10001')}/idporten-admin/\
        -Dbaseurl=http://${host}:${servicePorts.get('eid-atest-admin:10006')}/serviceprovider/\
        -DminIDOnTheFlyUrl=http://${host}:${servicePorts.get('eid-atest-idp-app:10001')}/minid_filegateway/\
        -DseleniumUrl=http://${host}:${servicePorts.get('selenium:4444')}/wd/hub\
        -DdatabaseUrl=${host}:${servicePorts.get('eid-atest-db:3306')}
    """
    cucumber 'system-tests/target/cucumber-report.json'
    new VerificationTestResult(
            success: status == 0,
            reportUrl: "${env.BUILD_URL}cucumber-html-reports/overview-features.html"
    )
}

void deletePublished(def environmentId, def version) {
    // TODO: Make environment aware (includes Nexus support and repository configuration)
    if (!environments.isMavenDeletionSupported(environmentId)) {
        echo "Maven artifact deletion is not supported for environment ${environmentId}"
        return
    }
    try {
        echo "Deleting artifacts for version ${version}"
        url = "http://eid-artifactory.dmz.local:8080/artifactory/api/search/gavc?v=${version}&repos=libs-release-local"
        httpresponse = httpRequest url
        response = new JsonSlurperClassic().parseText(httpresponse.content)
        Set<String> toDel = new HashSet<String>()
        response['results'].each{ item ->
            toDel.add(item['uri'].minus('api/storage/').minus(item['uri'].tokenize("/").last()))
        }
        withCredentials([string(credentialsId: 'artifactory', variable: 'artifactory')]) {
            toDel.each{ item ->
                try {
                    httpRequest customHeaders: [[name: 'X-JFrog-Art-Api', value: artifactory, maskValue: true]], httpMode: 'DELETE', url: item
                }
                catch (e){
                    echo "Failed to delete Maven artifact ${item}: ${e.message}"
                }
            }
        }
    } catch (e) {
        echo "Failed to delete Maven artifacts: ${e.message}"
    }
}

@NonCPS
private void settingsFileWithDockerAndJava(def dockerServerId, def dockerUserName, def dockerPassword, def javaServerId, def javaUserName, def javaPassword) {
    String settingsTemplate = libraryResource 'mavenSettingsWithDockerAndJava.xml'
    Map binding = [
            'dockerServerId': dockerServerId,
            'dockerUserName': dockerUserName,
            'dockerPassword': dockerPassword,
            'javaServerId': javaServerId,
            'javaUserName': javaUserName,
            'javaPassword': javaPassword
    ]
    String settings = bind settingsTemplate, binding
    writeToFile settings
}

@NonCPS
private void settingsFileWithJava(def javaServerId, def javaUserName, def javaPassword) {
    String settingsTemplate = libraryResource 'mavenSettingsWithJava.xml'
    Map binding = [
            'javaServerId': javaServerId,
            'javaUserName': javaUserName,
            'javaPassword': javaPassword
    ]
    String settings = bind settingsTemplate, binding
    writeToFile settings
}

@NonCPS
private void settingsFile() {
    String settings = libraryResource 'mavenSettings.xml'
    writeToFile settings
}

@NonCPS
private static String bind(String template, Map binding) {
    new SimpleTemplateEngine().createTemplate(template).make(binding).toString()
}

@NonCPS
private String writeToFile(String settings) {
    sh(returnStdout: true, script: "file=/tmp/settings\${RANDOM}.tmp && echo '${settings}' > \${file} && echo \${file}").trim()
}
