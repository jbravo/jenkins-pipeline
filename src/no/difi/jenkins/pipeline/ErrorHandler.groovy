package no.difi.jenkins.pipeline

void trigger(String message) {
    echo "Stage '${STAGE_NAME}' failed: ${message}" // Errors are not logged in post-steps, so log here
    error "Stage '${STAGE_NAME}' failed: ${message}"
}
