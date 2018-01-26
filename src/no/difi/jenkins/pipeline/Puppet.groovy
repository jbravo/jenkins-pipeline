package no.difi.jenkins.pipeline

Map config

void deploy(def master, def version, def puppetModules, def librarianModules, def puppetApplyList) {
    sshagent([config.masters[master as String].sshKey]) {
        tagPuppetModules(version)
        updateHiera(version, puppetModules)
        updateControl(version, librarianModules)
        apply(master, librarianModules, puppetApplyList)
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
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        local platformFile=nodes/systest/platform.yaml
        git clone --branch=master git@eid-gitlab.dmz.local:puppet/puppet_hiera.git \${workDirectory}
        cd \${workDirectory}
        for module in ${modules}; do
            sed -i "s/\\(\${module}::component_version:\\s\\).*/\\1\\"${version}\\"/i" \${platformFile}
        done
        git add \${platformFile}
        git config --local user.name 'Jenkins'
        git config --local user.email 'jenkins@difi.no'
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
        local workDirectory=\$(mktemp -d /tmp/XXXXXXXXXXXX)
        local puppetFile=Puppetfile
        git clone -b systest --single-branch git@eid-gitlab.dmz.local:puppet/puppet_control.git \${workDirectory}
        cd \${workDirectory}
        for module in ${modules}; do
        sed -ie "/\${module}/ s/:ref => '[^']*'/:ref => '${version}'/" \${puppetFile}
        done
        git add \${puppetFile}
        git config --local user.name 'Jenkins'
        git config --local user.email 'jenkins@difi.no'
        git commit -m "${env.JOB_NAME} #${env.BUILD_NUMBER}: Updated version of modules ${modules} to ${version}" \${puppetFile}
        git push
        cd -
        rm -rf \${workDirectory}
    }

    updateControl
    """
}

private void apply(def master, def librarianModules, def applyParametersList) {
    def masterHost = config.masters[master as String].host
    def masterHostUser = config.masters[master as String].user
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

