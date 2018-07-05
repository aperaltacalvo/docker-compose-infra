#!groovy

import net.sf.*;
import net.sf.json.*;
import net.sf.json.groovy.*;


def faaSDocker = new es.securitasdirect.faas.FaaSDocker()
def repo = "${REPO_URL}"
def outBranchTokenized = "${BRANCH_NAME}"
def repository
def currentBranch
def mvnHome
def buildInfo
def server
def artifactoryMaven
def sonarSources = 'src'
def containerEXTServPort = 19764
def sonarqubeScannerHome
def buildName
def xldBuildNumber
def commitMessage = "No new commits"
def report = new es.securitasdirect.faas.Report()
def buildTip = "ABORTED"
def ENVIRONMENT_TO_DEPLOY = 'dev'
def application_name = "${APP_NAME}"
def outCommit
def outCommitTokenized
def commitLast

def getBranchName = {
    println("[FaaS] Obtaining branch name")
    if(outBranchTokenized == "none") {
        outCommit = sh script: "git for-each-ref --sort=-committerdate | head -n 1", returnStdout: true
        println("[FaaS] Last Commits in reflog: " + outCommit)
        outCommitTokenized = outCommit.trim().tokenize()
        commitLast = outCommitTokenized.first()
        def outBranch = sh script: "git show-ref | grep origin | grep ${commitLast} | head -n 1", returnStdout: true
        println("[FaaS] Commit on Branch: " + outBranch)
        println("[FaaS] Getting outbranch tokenized")
        outBranchTokenized = outBranch.trim().tokenize('/')
        println("[FaaS] Outbranch tokenized: " + outBranchTokenized)

        if (outBranchTokenized.get(outBranchTokenized.size() - 2).contains("feature")) {
            return "feature/" + outBranchTokenized.last()

        } else if (outBranchTokenized.get(outBranchTokenized.size() - 2).contains("develop")) {
            return "develop/" + outBranchTokenized.last()

        } else if (outBranchTokenized.get(outBranchTokenized.size() - 2).contains("hotfix")) {
            return "hotfix/" + outBranchTokenized.last()

        } else if (outBranchTokenized.get(outBranchTokenized.size() - 2).contains("release")) {
            return "release/" + outBranchTokenized.last()

        } else {
            return outBranchTokenized.last()

        }
    } else {
        return outBranchTokenized
    }
}

//Manage artifactory and branches information functions
def getLastCommintAndBranchInfo = {
    sshagent (['faas_rsa']) {
        currentBranch = getBranchName().toString()
        println("[Faas] Current branch: "+currentBranch)
        // Clone the target repository on the selected branch
        git credentialsId: 'faas_rsa', poll: false, url: "${repo}", branch: "${currentBranch}"
        println("[FaaS] Working on branch: " + currentBranch)
        mvnHome = tool 'M3'

        repository = repo.tokenize('/').last()

        println("[FaaS] repository: "+repository)
        // Retrieve SHA-1 revision from GIT
        sh 'git rev-parse HEAD > commit'
        revision = readFile('commit').trim()

        // Save source code so we don't need to get it every time and also avoids conflicts
        //stash excludes: '**/target/', includes: '**', name: 'sources'
        // Retrieve Project GAV
        pom = readMavenPom file: 'pom.xml'
        groupId = pom.groupId
        artifactId = pom.artifactId
        version = pom.version
        groupId_slashes = groupId.replaceAll(/\./, "/")
        // Print Project Info
        println """[FaaS] -------------------------------------------------------${nl.getNewLine()}[FaaS] PROJECT INFO:${nl.getNewLine()}[FaaS] -------------------------------------------------------${nl.getNewLine()}[FaaS]       groupId = ${groupId}${nl.getNewLine()}[FaaS]    artifactId = ${artifactId}${nl.getNewLine()}[FaaS]      version = ${version}${nl.getNewLine()}[FaaS]    branch = ${currentBranch}${nl.getNewLine()}[FaaS]       revision = ${revision}${nl.getNewLine()}[FaaS] -------------------------------------------------------"""
        // Change Build Name format
        buildName = repository + '-' + revision.substring(0,8) + '-' + version + '-[' +env.BUILD_NUMBER + ']'
        currentBuild.displayName = buildName
        println("[FaaS] Reading commit message")
        commitMessage = sh (script: 'git log -n 1 --pretty=format:\'%s\'', returnStdout: true).trim()
        println("[FaaS] commit message detected "+commitMessage)
    }
}

def setMavenBuildInformation = {
    // Retrieve Artifactory Server Instance
    server = Artifactory.server('Artifactory')
    // Configure Artifactory Maven Run
    artifactoryMaven = Artifactory.newMavenBuild()
    artifactoryMaven.tool = 'M3'
    artifactoryMaven.deployer releaseRepo:'staging', snapshotRepo:'snapshot', server: server
    artifactoryMaven.resolver releaseRepo:'securitasdirect', snapshotRepo:'securitasdirect', server: server
    buildInfo = Artifactory.newBuildInfo()
    buildInfo.env.capture = true
    xldBuildNumber = buildInfo.number.padLeft(nl.xldBNL(), '0')
    println("[FaaS] Inserting custom properties in build-info")
    // Custom Properties for Artifactory
    println("[FaaS] repo: " + repo)
    println("[FaaS] repository: " + repository)
    artifactoryMaven.deployer.addProperty("vcs.repo", repository)
    println("[FaaS] faasId: " + buildName)
    artifactoryMaven.deployer.addProperty("faas.id", buildName)
    println("[FaaS] commit: " + revision)
    artifactoryMaven.deployer.addProperty("vcs.revision", revision)
    println("[FaaS] branch: " + currentBranch)
    artifactoryMaven.deployer.addProperty("vcs.branch", currentBranch)
    println("[FaaS] groupId: " + groupId)
    artifactoryMaven.deployer.addProperty("project.groupId", groupId)
    println("[FaaS] artifactId: " + artifactId)
    artifactoryMaven.deployer.addProperty("project.artifactId", artifactId)
    println("[FaaS] version: " + version)
    artifactoryMaven.deployer.addProperty("project.version", version)
}

//Sonar Scanner function
def launchSonarScanner = {
    unstash 'sources'
    // Retrieve SonarQube Scanner Tool
    sonarqubeScannerHome = tool name: 'SonarQubeScanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
    // Run Code Inspection with SonarQube Scanner
    withSonarQubeEnv('SonarQube') {
        sh "${sonarqubeScannerHome}/bin/sonar-scanner -Dsonar.projectKey=${groupId}.${artifactId} -Dsonar.projectName=${artifactId} -Dsonar.projectVersion=${version} -Dsonar.sources=${sonarSources} -Dsonar.sourceEncoding=UTF-8 -Dsonar.java.binaries=target/classes -Dsonar.jacoco.reportPaths=target/jacoco.exec -Dsonar.java.coveragePlugin=jacoco -Dsonar.test.exclusions=src/test/java/**/*.* -Dsonar.exclusions=src/test/java/**/*.*,**/*DTO.java,**/domain/entity/*,**/config/*,**/config/dbmigrations/*,**/StartupBootable.java,**/Application.java,**/exception/*"
    }

}

def getEnvironmentToDeploy = {

    ENVIRONMENT_TO_DEPLOY = 'dev'
    if(currentBranch.toLowerCase().matches(".*release.*") || currentBranch.toLowerCase().matches(".*hotfix.*")){
        ENVIRONMENT_TO_DEPLOY = 'pre'
    }
}

//Pipeline definition
pipeline() {
    agent{label 'jee'}
    options {
        skipStagesAfterUnstable()
    }
    stages{
        stage('Obtain project information'){
            steps {
                script {
                    try {
                        // Truncate workspace folder
                        println("[FaaS] Cleaning workspace")
                        deleteDir()
                        // Checkout repository
                        git credentialsId: 'faas_rsa', poll: false, url: "${repo}"
                        // Look for the last commit and branch
                        getLastCommintAndBranchInfo()
                    }catch(e){
                        println("[FaaS] Obtain project information phase has failed")
                        currentBuild.result = "FAILED"
                        buildTip = "Obtain project information failed"
                        sh 'exit 1'
                    }
                }
            }
        }

        stage ('Build'){
            // Work always on repository subfolder because this is a generic job
            steps{
                script{
                    try {
                        println("[FaaS] Preparing build environment")
                        setMavenBuildInformation()
                        println("[FaaS] Running build")
                        artifactoryMaven.run pom: 'pom.xml', goals: "clean install -U", buildInfo: buildInfo
                        // Publish buildInfo
                        println("[FaaS] Publishing build-info")
                        server.publishBuildInfo buildInfo
                        //stash include: '**/*.jar', name:'app'
                        stash includes: '**', name: 'sources'
                        stash includes: 'docker/**', name: 'Dockerfile'
                    }catch(e){
                        println("[FaaS] Project build has failed")
                        currentBuild.result = "FAILED"
                        buildTip = "Project build failed"
                        println(e)
                        sh 'exit 1'
                    }
                }
            }
        }

        stage ('Code Inspection'){
            agent{label 'sonarqube'}
            steps{
                script{
                    try{
                        echo "[FaaS] Cleaning workspace"
                        deleteDir()
                        launchSonarScanner()

                    }catch (e) {
                        // If there was an exception thrown, the build failed
                        println("[FaaS] Sonar analysis has failed")
                        currentBuild.result = "FAILED"
                        buildTip = "Sonar analysis failed"
                        sh 'exit 1'

                    }
                }
            }
        }

        stage ('Docker Registry'){
            agent{label 'docker'}
                    steps {
                        script {

                        try {
                            // Work always on repository subfolder because this is a generic job

                            // Truncate workspace folder
                            echo "[FaaS] Cleaning workspace"
                            deleteDir()
                            // Recover packaged binaries
                            //unstash 'binaries'
                            //unstash 'app'
                            unstash 'Dockerfile'
                            //unstash 'sources'


                            sh 'ls docker'
                            sh 'pwd'


                            // Get an available port from FaaS Docker Utils
                            containerEXTServPort = faaSDocker.getDockerContainerFreePort()
                            // Start Docker container and store containerId
                            sh "docker build -f docker/Dockerfile -t faas.securitasdirect.local:1443/${artifactId}:${version} --build-arg NAME=${artifactId} --build-arg VERSION=${version} ."
                            sh "docker push faas.securitasdirect.local:1443/${artifactId}:${version} > containerId"
                            containerId = readFile('containerId').trim()
                            echo "[FaaS] DOCKER CONTAINER ID = $containerId"
                            sh "docker rmi -f faas.securitasdirect.local:1443/${artifactId}:${version}"
                            sleep(20)
                            buildTip="SUCCESS"

                        } catch (e) {
                            println("[FaaS] Error in docker image build")
                        //    notified = true
                            currentBuild.result = "FAILED"
                            buildTip = "Error in docker image build"

                            //     faaSUtils.notifyRelease(currentBuild.result)
                            sh "docker rmi -f faas.securitasdirect.local:1443/${artifactId}:${version}"
                            sh 'exit 1'
                        }
                    }
            }
        }
        stage('Launch continuous deployment pipeline'){
            when {
                expression {
                    return currentBranch.toLowerCase().matches(".*develop.*") && buildTip.matches("SUCCESS") && !application_name.matches("none")
                }
            }
            steps {
                script {
                    try {
                        getEnvironmentToDeploy()
                        build job: 'Kubernetes-Continuous-Deployment', parameters: [[$class: 'StringParameterValue', name: 'CLUSTER_TO_DEPLOY', value: ENVIRONMENT_TO_DEPLOY], [$class: 'StringParameterValue', name: 'DOCKER_IMAGE_NAME', value:artifactId ], [$class: 'StringParameterValue', name: 'DOCKER_IMAGE_VERSION', value: version], [$class: 'StringParameterValue', name: 'APP_NAME', value: application_name.toLowerCase()]], wait: false

                    }catch (e){
                        println("[FaaS] Error in continuous deployment call")
                        currentBuild.result = "FAILED"
                        buildTip = "Error in continuous deployment call"
                        sh 'exit 1'
                    }

                }
            }
        }

    }
    post {
        always {
            script {
                    println("[FaaS] buildTip sent to mail notification: " + buildTip)
                    report.emailNotification(buildTip, buildName, commitMessage,currentBranch)

            }
        }

    }
}