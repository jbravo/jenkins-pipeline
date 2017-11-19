def call(String commitId, String issueSummary, String issueId, String crucibleUrl, String crucibleRepository, String crucibleProjectKey, String username, String password) {
    String request = crucibleRequest commitId, issueSummary, issueId, crucibleProjectKey, crucibleRepository
    echo "Creating review on ${crucibleUrl} using the following request:\n${request}"
    sh """
    #!/usr/bin/env bash    
    statusCode=\$(curl \
        -u '${username}:${password}' \
        -H 'Content-Type: application/json' \
        -fsS '${crucibleUrl}/rest-service/reviews-v1' \
        -w '%{http_code}' \
        -d '${request}') ||
    {
        [ "\${statusCode}" == "401" ] && { >&2 echo "Incorrect credentials for ${crucibleUrl}"; return 1; }
        [ "\${statusCode}" == "500" ] && { >&2 echo "Is the repository called ${crucibleRepository} and the project key ${crucibleProjectKey} in Fisheye/Crucible?"; return 1; }
        { >&2 echo "Failed to create review. HTTP response code is \${statusCode} and curl error code is \$?"; return  1; }
    }
    """
}
