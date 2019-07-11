package no.difi.jenkins.pipeline
import static groovy.json.JsonOutput.toJson

import groovy.json.JsonSlurperClassic


Environments environments
ErrorHandler errorHandler

String getProjectID(String projectName) {
    cleanProjectName = cleanProjectName(projectName)
    withCredentials([string(credentialsId: 'dependency-track-api', variable: 'apiKey')]) {
        httpresponse = httpRequest(
                url: "http://dependency-track:8080/api/v1/project?name=${cleanProjectName}",
                httpMode: 'GET',
                contentType: 'APPLICATION_JSON_UTF8',
                customHeaders: [[maskValue: true, name: 'X-Api-Key', value: apiKey]],
        )
    }
    response = new JsonSlurperClassic().parseText(httpresponse.content)
    if (response.isEmpty()){
        return createProject(cleanProjectName)
    }
    return response['uuid'].last().toString()


}

private String createProject(String projectName){
    withCredentials([string(credentialsId: 'dependency-track-api', variable: 'apiKey')]) {
        httpresponse = httpRequest(
                url: "http://dependency-track:8080/api/v1/project",
                httpMode: 'PUT',
                contentType: 'APPLICATION_JSON_UTF8',
                customHeaders: [[maskValue: true, name: 'X-Api-Key', value: apiKey]],
                requestBody: toJson(
                        [
                                name: "${projectName}"
                        ]
                )
        )
    }
    return (new JsonSlurperClassic().parseText(httpresponse.content))['uuid'].toString()
}

private  String cleanProjectName(String projectName){
    return projectName.replaceAll("/","_").replaceAll("%2F","_")
}
void deleteProject(String projectName) {
    projectID = getProjectID(projectName)
    withCredentials([string(credentialsId: 'dependency-track-api', variable: 'apiKey')]) {
        httpresponse = httpRequest(
                url: "http://dependency-track:8080/api/v1/project/${projectID}",
                httpMode: 'DELETE',
                contentType: 'APPLICATION_JSON_UTF8',
                customHeaders: [[maskValue: true, name: 'X-Api-Key', value: apiKey]],
        )
    }
}
