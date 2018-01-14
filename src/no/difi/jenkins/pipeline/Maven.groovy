package no.difi.jenkins.pipeline

import groovy.json.JsonSlurperClassic
import groovy.text.SimpleTemplateEngine

void verify(def options) {
    String settingsFile = settingsFile()
    env.MAVEN_OPTS = options ?: ""
    sh "mvn clean verify -B -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

void deployDockerAndJava(
        def version, def mavenOptions, def parallel,
        def dockerRegistry,
        def javaRepository, def javaUserName, def javaPassword
) {
    currentBuild.description = "Publishing artifacts with version ${version} from commit ${GIT_COMMIT}"
    String settingsFile
    withCredentials([usernamePassword(
            credentialsId: Docker.credentialsId(dockerRegistry),
            passwordVariable: 'dockerPassword',
            usernameVariable: 'dockerUsername')]
    ) {
        settingsFile = settingsFileWithDockerAndJava dockerRegistry, env.dockerUsername, env.dockerPassword, "javaRepository", javaUserName, javaPassword
    }
    env.MAVEN_OPTS = mavenOptions ?: ""
    String parallelOptions = parallel ? "-T 1C" : ""
    sh "mvn versions:set -B -DnewVersion=${version}"
    sh "mvn deploy -DdeployAtEnd=true -DaltDeploymentRepository=javaRepository::default::${javaRepository} -B ${parallelOptions} -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

void deployJava(
        def version, def mavenOptions,
        def javaRepository, def javaUserName, def javaPassword
) {
    currentBuild.description = "Publishing artifacts with version ${version} from commit ${GIT_COMMIT}"
    String settingsFile = settingsFileWithJava "javaRepository", javaUserName, javaPassword
    env.MAVEN_OPTS = mavenOptions ?: ""
    sh "mvn versions:set -B -DnewVersion=${version}"
    sh "mvn deploy -B -T 1C -DdeployAtEnd=true -DaltDeploymentRepository=javaRepository::default::${javaRepository} -s ${settingsFile}"
    sh "rm ${settingsFile}"
}

boolean systemTestsSupported() {
    int status = sh(returnStatus: true, script: "[ -e ${WORKSPACE}/system-tests ]")
    if (status == 0) {
        echo "System tests are supported"
        return true
    } else {
        echo "System tests are not supported"
        return false
    }
}

void runSystemTests(def verificationHostSshKey, def verificationHostUser, def verificationHostName, def stackName) {
    if (!systemTestsSupported()) return
    Map servicePorts = new Docker().servicePorts(
            verificationHostSshKey, verificationHostUser, verificationHostName, stackName,
            'eid-atest-admin', 'eid-atest-idp-app', 'selenium', 'eid-atest-db'
    )
    sh """
        mvn verify -pl system-tests -PsystemTests -B\
        -DadminDirectBaseURL=http://${verificationHostName}:${servicePorts.get('eid-atest-admin')}/idporten-admin/\
        -DminIDOnTheFlyUrl=http://${verificationHostName}:${servicePorts.get('eid-atest-idp-app')}/minid_filegateway/\
        -DseleniumUrl=http://${verificationHostName}:${servicePorts.get('selenium')}/wd/hub\
        -DdatabaseUrl=${verificationHostName}:${servicePorts.get('eid-atest-db')}
    """
}

void deletePublished(def version) {
    try {
        echo "Deleting artifacts for rejected version ${version}"
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
                catch (Exception e){
                    echo e.toString()
                }
            }
        }
    } catch (Exception e) {
        echo e.toString()
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
