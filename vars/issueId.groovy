def call() {
    return env.BRANCH_NAME.tokenize('/')[-1]
}
