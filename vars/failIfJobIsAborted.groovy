def call() {
    if (env.jobAborted == 'true')
        error('Job was aborted')
}