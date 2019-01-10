package no.difi.jenkins.pipeline
import static groovy.json.JsonOutput.toJson

Environments environments

void deploy(def environmentId, def version, def puppetModules, def librarianModules, def puppetApplyList) {
    sshagent([environments.puppetMasterSshKey(environmentId)]) {
        tagPuppetModules(version)
        updateHiera(version, puppetModules)
        updateControl(version, librarianModules)
        apply(environmentId, librarianModules, puppetApplyList)
    }
}

void deploy2(def environmentId, def version, def puppetModules, def puppetApplyList) {
    sshagent([environments.puppetMasterSshKey(environmentId)]) {
        if (moduleNames().size() == 0)
            return
        moduleNames().each { moduleName ->
            createRepo(moduleName, version)
            commitRepo(moduleName, version)
        }
        updateHiera(version, puppetModules)
        updateControl2(version, flattenList(moduleNames(),''))
        apply(environmentId, flattenList(moduleNames(),'DIFI-'), puppetApplyList)
    }
}
private void tagPuppetModules(version) {
    println "Tagging Puppet modules with ${version}"
    sh """#!/usr/bin/env bash
    
    tagRepo() {
        local repo=\$1
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        git clone --bare \${repo} \${workDirectory}
        cd \${workDirectory}
        git tag ${version}
        git push --tag
        cd -
        rm -rf \${workDirectory}
    }
    
    tagRepo git@eid-gitlab.dmz.local:puppet/puppet_modules.git
    tagRepo git@eid-gitlab.dmz.local:puppet/puppet_modules_oidc.git
    """
}

private void updateHiera(version, modules) {
    println "Updating version of modules ${modules} to ${version}"

    sh """#!/usr/bin/env bash
    
    updateVersions() {
        local gitUserName=\$(git config --get user.name)
        local gitEmail=\$(git config --get user.email)
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        local platformFile=nodes/systest/platform.yaml
        git clone --branch=master git@eid-gitlab.dmz.local:puppet/puppet_hiera.git \${workDirectory}
        cd \${workDirectory}
        for module in ${modules}; do
            sed -i "s/\\(\${module}::component_version:\\s\\).*/\\1\\"${version}\\"/i" \${platformFile}
        done
        git add \${platformFile}
        git config --local user.name "\${gitUserName}"
        git config --local user.email "\${gitEmail}"
        git commit -m "${env.JOB_NAME} #${env.BUILD_NUMBER}: Updated version of modules ${modules} to ${version}" \${platformFile}
        git push
        cd -
        rm -rf \${workDirectory}
    }
    
    updateVersions
    """
}

private void updateControl(version, modules) {
    println "Updating version of modules ${modules} to ${version}"

    sh """#!/usr/bin/env bash

    updateControl() {
        local gitUserName=\$(git config --get user.name)
        local gitEmail=\$(git config --get user.email)
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        local puppetFile=Puppetfile
        git clone -b systest --single-branch git@eid-gitlab.dmz.local:puppet/puppet_control.git \${workDirectory}
        cd \${workDirectory}
        for module in ${modules}; do
        sed -ie "/\${module}/ s/:ref => '[^']*'/:ref => '${version}'/" \${puppetFile}
        done
        git add \${puppetFile}
        git config --local user.name "\${gitUserName}"
        git config --local user.email "\${gitEmail}"
        git commit -m "${env.JOB_NAME} #${env.BUILD_NUMBER}: Updated version of modules ${modules} to ${version}" \${puppetFile}
        git push
        cd -
        rm -rf \${workDirectory}
    }

    updateControl
    """
}

private void apply(def environmentId, def librarianModules, def applyParametersList) {
    def masterHost = environments.puppetMasterHost(environmentId)
    def masterHostUser = environments.puppetMasterUser(environmentId)
    def librarianFolder = '/etc/puppet/environments/systest'
    def hieraFolder = '/etc/puppet/hieradata'

    sh """#!/usr/bin/env bash

    updateMaster() {
        ssh -tt -o StrictHostKeyChecking=no ${masterHostUser}@${masterHost} \
        "cd ${hieraFolder} && \
        echo 'Updating Hiera-configuration at ${masterHost}:${hieraFolder}' && \
        sudo git pull && \
        cd ${librarianFolder} && \
        echo 'Updating Librarian-configuration at ${masterHost}:${librarianFolder}' && \
        sudo git pull && \
        sudo librarian-puppet install && \
        sudo librarian-puppet update ${librarianModules}"
    }

    updateMaster
    """

    applyParametersList.each { parameters ->
        String host = parameters.tokenize().getAt(0)
        String moduleList = parameters.tokenize().getAt(1)
        sh """#!/usr/bin/env bash
        set +e

        apply() {
            ssh -tt -o StrictHostKeyChecking=no jenkins@${host} sudo -i puppet agent --test --tags=${moduleList}
            local exitCode=\$?
            [[ \${exitCode} -eq 0 ]] && { echo "Apply succeeded as the system was already in the desired state"; return 0; }
            [[ \${exitCode} -eq 1 ]] && { echo "Apply failed or wasn't attempted due to another run already in progress"; return 1; }
            [[ \${exitCode} -eq 2 ]] && { echo "Apply succeeded and some resources were changed"; return 0; }
            [[ \${exitCode} -eq 4 ]] && { echo "Apply succeeded but some resources failed"; return 1; }
            [[ \${exitCode} -eq 6 ]] && { echo "Apply succeeded -- some resources changed and some failed"; return 1; }
        }
        
        apply
        """
    }

}

private void createRepo(def module,def version){
    println "Creating release repo for version ${version} of module ${module}"
    withCredentials([string(credentialsId: 'gitlab-api', variable: 'apikey')]){
        httpRequest(
                url: 'http://eid-gitlab.dmz.local/api/v4/projects',
                httpMode: 'POST',
                contentType: 'APPLICATION_JSON_UTF8',
                customHeaders: [[maskValue: true, name: 'Private-Token', value: apikey]],
                requestBody: toJson(
                        [
                                name: "${module}-${version}",
                                namespace_id: '40',
                                visibility: 'public',
                        ]
                )
        )
    }
}

private void commitRepo(def module,def version) {
    println "Commitig  version ${version} of module ${module} to release repo"
    sh """#!/usr/bin/env bash

    commitRepo() {
        local gitUserName=\$(git config --get user.name)
        local gitEmail=\$(git config --get user.email)
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        git clone git@eid-gitlab.dmz.local:puppet_releases/${module}-${version}.git \${workDirectory}
        cp -r puppet_modules/${module}/* \${workDirectory}
        cd \${workDirectory}
        git add .
        git config --local user.name "\${gitUserName}"
        git config --local user.email "\${gitEmail}"
        git commit -m "${env.JOB_NAME} #${env.BUILD_NUMBER}: Create release version of ${module}-${version}" 
        git push
        cd -
        rm -rf \${workDirectory}
    }

    commitRepo
    """

}



private void updateControl2(def version,def  modules) {
    println "Updating version of modules ${modules} to ${version}"


    sh """#!/usr/bin/env bash

    updateControl() {
        local gitUserName=\$(git config --get user.name)
        local gitEmail=\$(git config --get user.email)
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        local puppetFile=Puppetfile
        git clone -b systest --single-branch git@eid-gitlab.dmz.local:puppet/puppet_control.git \${workDirectory}
        cd \${workDirectory}
        for module in ${modules}; do
        sed -ie "s/\${module}-.*/\${module}-${version}.git'/" \${puppetFile}
        done
        git add \${puppetFile}
        git config --local user.name "\${gitUserName}"
        git config --local user.email "\${gitEmail}"
        git commit -m "${env.JOB_NAME} #${env.BUILD_NUMBER}: Updated version of modules ${modules} to ${version}" \${puppetFile}
        git push
        cd -
        rm -rf \${workDirectory}
    }

    updateControl
    """
}

private List<String> moduleNames() {
    String result = sh(returnStdout: true, script: "[ -e ${WORKSPACE}/puppet_modules ] && find ${WORKSPACE}/puppet_modules -maxdepth 1 -mindepth 1 -type d -exec basename {} \\; || echo -n")
    if (result.trim().isEmpty())
        return emptyList()
    return result.split("\\s+")
}
private String flattenList(def list, def prefix ) {
    flatlist = ''
    list.each { item ->
        flatlist = flatlist + prefix + item + ' '
    }
    return flatlist
}