def call(String crucibleUrl, String repositoryName, String username, String password) {
    echo "Waiting for incremental indexing to complete..."
    sh """
    #!/usr/bin/env bash    
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