def call() {
    if (env.codeApproved == "false")
        error("Code was not approved")
}