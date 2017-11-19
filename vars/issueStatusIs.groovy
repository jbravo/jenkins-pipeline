boolean call(def targetStatus) {
    def issueStatus = issueStatus()
    echo "Issue status is ${issueStatus}"
    issueStatus == targetStatus
}
