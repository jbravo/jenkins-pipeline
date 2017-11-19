import groovy.text.SimpleTemplateEngine

String call(String commitId, String issueSummary, String issueId, String crucibleProjectKey, String crucibleRepository) {
    String requestTemplate = libraryResource 'crucibleRequest.json'
    Map binding = [
            'crucibleProjectKey' : crucibleProjectKey,
            'commitMessage' : issueSummary.replaceAll('"', '\\\\"'),
            'issueId' : issueId,
            'commitId' : commitId,
            'repositoryName' : crucibleRepository
    ]
    bind(requestTemplate, binding)
}

@NonCPS
static String bind(String template, Map binding) {
    new SimpleTemplateEngine().createTemplate(template).make(binding).toString()
}
