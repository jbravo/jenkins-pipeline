import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(def targetStatus) {
    env.jobAborted = 'false'
    try {
        retry(count: 1000000) {
            if (!issueStatusIs(targetStatus)) {
                sleep 10
                error "Waiting until issue status is ${targetStatus}..."
            }
        }
    } catch (FlowInterruptedException e) {
        env.jobAborted = "true"
    }
}
