package no.difi.jenkins.pipeline

import groovy.text.SimpleTemplateEngine

void synchronize(String crucibleUrl, String repositoryName, String username, String password) {
    echo "Waiting for incremental indexing to complete..."
    sh """#!/usr/bin/env bash    
    statusCode=\$(curl \
        -XPUT \
        -u "${username}:${password}" \
        -H "Content-Type: application/json" \
        -fsS "${crucibleUrl}/rest-service-fecru/admin/repositories/${repositoryName}/incremental-index?wait=true" \
        -w '%{http_code}') ||
    {
        [ "\${statusCode}" == "401" ] && { >&2 echo "Failed to perform incremental indexing. Incorrect credentials for ${crucibleUrl}"; return 1; }
        [ "\${statusCode}" == "500" ] && { >&2 echo "Failed to perform incremental indexing. Is the repository called ${repositoryName} in Fisheye/Crucible?"; return 1; }
        { >&2 echo "Failed to perform incremental indexing. HTTP response code is \${statusCode} and curl error code is \$?"; return  1; }
    }
    """
}

void createReview(String commitId, String issueSummary, String issueId, String crucibleUrl, String crucibleRepository, String crucibleProjectKey, String username, String password) {
    String request = request commitId, issueSummary, issueId, crucibleProjectKey, crucibleRepository
    echo "Creating review on ${crucibleUrl} using the following request:\n${request}"
    sh """#!/usr/bin/env bash    
    statusCode=\$(curl \
        -u '${username}:${password}' \
        -H 'Content-Type: application/json' \
        -w '%{http_code}' \
        -d '${request}' \
        -fsS '${crucibleUrl}/rest-service/reviews-v1') ||
    {
        [ "\${statusCode}" == "401" ] && { >&2 echo "Incorrect credentials for ${crucibleUrl}"; return 1; }
        [ "\${statusCode}" == "500" ] && { >&2 echo "Is the repository called ${crucibleRepository} and the project key ${crucibleProjectKey} in Fisheye/Crucible?"; return 1; }
        { >&2 echo "Failed to create review. HTTP response code is \${statusCode} and curl error code is \$?"; return  1; }
    }
    """
}

private String request(String commitId, String issueSummary, String issueId, String crucibleProjectKey, String crucibleRepository) {
    String requestTemplate = libraryResource 'crucibleRequest.json'
    String safeIssueSummary = issueSummary
            .replaceAll("'", '"')
            .replaceAll('"', '\\\\"')
    Map binding = [
            'crucibleProjectKey' : crucibleProjectKey,
            'commitMessage' : safeIssueSummary,
            'issueId' : issueId,
            'commitId' : commitId,
            'repositoryName' : crucibleRepository
    ]
    bind(requestTemplate, binding)
}

@NonCPS
private static String bind(String template, Map binding) {
    new SimpleTemplateEngine().createTemplate(template).make(binding).toString()
}
