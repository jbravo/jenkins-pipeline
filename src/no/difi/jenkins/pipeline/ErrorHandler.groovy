package no.difi.jenkins.pipeline

void trigger(String message) {
    error("Stage '${STAGE_NAME}' failed: ${message}")
}
