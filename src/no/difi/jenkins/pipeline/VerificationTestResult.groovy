package no.difi.jenkins.pipeline

class VerificationTestResult {

    private boolean success = false
    private String reportUrl

    boolean success() {
        success
    }

    String reportUrl() {
        reportUrl
    }


}
