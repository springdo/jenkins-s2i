#!/usr/bin/env groovy

//  list of apps
// devlop / master branches for each pipeline
def pipelineNames = ["dev", "test"]
def appName = "cc-fe"

//  Globals for across all the jobs
def gitBaseUrl = "https://github.com/springdo/react-starter-app"
def pipelineNamespace = "cc-ci-cd"
newLine = System.getProperty("line.separator")

def pipelineGeneratorVersion = "${JOB_NAME}.${BUILD_ID}"
// TODO - make this happen in the job creation loop
def projectDevNamespace = "cc-dev"
def projectTestNamespace = "cc-test"
def jenkinsGitCreds = "jenkins-id-as-seen-in-j-man"

//  Common functions repeated across the jobs
def buildWrappers(context) {
    context.ansiColorBuildWrapper {
        colorMapName('xterm')
    }
}

def notifySlack(context) {
    context.slackNotifier {
        notifyAborted(true)
        notifyBackToNormal(true)
        notifyFailure(true)
        notifyNotBuilt(true)
        notifyRegression(true)
        notifyRepeatedFailure(true)
        notifySuccess(true)
        notifyUnstable(true)
    }
}

def rotateLogs(context) {
    context.logRotator {
        daysToKeep(100)
        artifactNumToKeep(2)
    }
}

def coverageReport(context) {
    context.cobertura('coverage/clover.xml') {
        failNoReports(true)
        sourceEncoding('ASCII')
        // the following targets are added by default to check the method, line and conditional level coverage
        methodTarget(80, 40, 20)
        lineTarget(80, 40, 20)
        conditionalTarget(70, 40, 20)
    }
    context.publishHtml {
        report('coverage/lcov-report') {
            reportName('HTML Code Coverage Report')
            allowMissing(false)
            alwaysLinkToLastBuild(false)
        }
    }
}

pipelineNames.each {
    def pipelineName = it
    def buildImageName = it + "-" + appName + "-build"
    def bakeImageName = it + "-" + appName + "-bake"
    def deployImageName = it + "-" + appName + "-deploy"
    def projectNamespace = "cc-" + it
    def jobDescription = "THIS JOB WAS GENERATED BY THE JENKINS SEED JOB - ${pipelineGeneratorVersion}.  \n"  + it + "build job for the fe apps"

    job(buildImageName) {
        description(jobDescription)
        label('npm-build-pod')

        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)

            preScmSteps {
                // Fails the build when one of the steps fails.
                failOnError(true)
                // Adds build steps to be run before SCM checkout.
                steps {
                    //  TODO - add git creds here
                    shell('git config --global http.sslVerify false' + newLine +
                            'git config --global user.name jenkins' + newLine +
                            'git config --global user.email jenkins@cc.net')
                }
            }
        }
        scm {
            git {
                remote {
                    name('origin')
                    url(gitBaseUrl)
                    credentials(jenkinsGitCreds)
                }
                if (pipelineName.contains('test')){
                    branch('master')
                }
                else {
                    branch('develop')
                }
            }
        }
        if (pipelineName.contains('dev')){
            triggers {
                cron('H/60 H/2 * * *')
            }
        }
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'NAME=' + appName  + newLine +
                        'export NODE_ENV=ci' + newLine +
                        'set -o xtrace' + newLine +
                        'scl enable rh-nodejs6 \'npm install\'' + newLine +
                        'scl enable rh-nodejs6 \'./node_modules/.bin/gulp lint\'' + newLine +
                        'scl enable rh-nodejs6 \'./node_modules/.bin/gulp test\'' + newLine +
                        'mkdir package-contents' + newLine +
                        'mv server Dockerfile package.json server.js package-contents' + newLine +
                        'oc patch bc ${NAME} -p "spec:' + newLine +
                        '   nodeSelector:' + newLine +
                        '   output:' + newLine +
                        '     to:' + newLine +
                        '       kind: ImageStreamTag' + newLine +
                        '       name: \'${NAME}:${JOB_NAME}.${BUILD_NUMBER}\'"' + newLine +
                        'oc start-build ${NAME} --from-dir=package-contents/ --follow')
            }
        }
        publishers {
            // nexus upload
            postBuildScripts {
                steps {
                    shell('export WAR_NAME=$(ls api/build/libs/)' + newLine +
                        'curl -v -F r=releases \\' + newLine +
                                '-F hasPom=false \\' + newLine +
                                '-F e=zip \\' + newLine +
                                '-F g=com.example.react.fe \\' + newLine +
                                '-F a=war \\' + newLine +
                                '-F v=0.0.1-${JOB_NAME}.${BUILD_NUMBER} \\' + newLine +
                                '-F p=zip \\' + newLine +
                                '-F file=@api/build/libs/${WAR_NAME}\\' + newLine +
                                '-u admin:admin123 http://nexus-v2.cc-ci-cd.svc:8081/nexus/service/local/artifact/maven/content')
                }
            }

            archiveArtifacts('**')

            coverageReport(delegate)

            xUnitPublisher {
                tools {
                    jUnitType {
                        pattern('test-report.xml')
                        skipNoTestFiles(false)
                        failIfNotNew(true)
                        deleteOutputFiles(true)
                        stopProcessingIfError(true)
                    }
                }
                thresholds {
                    failedThreshold {
                        failureThreshold('0')
                        unstableThreshold('')
                        unstableNewThreshold('')
                        failureNewThreshold('')
                    }
                }

                thresholdMode(0)
                testTimeMargin('3000')
            }
            // git publisher
            git {
                tag(gitBaseUrl, "${JOB_NAME}.${BUILD_NUMBER}") {
                    create(true)
                    message("Automated commit by jenkins from ${JOB_NAME}.${BUILD_NUMBER}")
                }
            }

            downstreamParameterized {
                trigger(bakeImageName) {
                    condition('UNSTABLE_OR_BETTER')
                    parameters {
                        predefinedBuildParameters{
                            properties("BUILD_TAG=\${JOB_NAME}.\${BUILD_NUMBER}")
                            textParamValueOnNewLine(true)
                        }
                    }
                }
            }

            notifySlack(delegate)
        }
    }


    job(bakeImageName) {
        description(jobDescription)
        parameters{
            string{
                name("BUILD_TAG")
                defaultValue("my-app-build.1234")
                description("The BUILD_TAG is the \${JOB_NAME}.\${BUILD_NUMBER} of the successful build to be promoted.")
            }
        }
        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)
        }
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'set -o xtrace' + newLine +
                        '# WIPE PREVIOUS BINARY' + newLine +
                        'rm -rf *.zip *.war' + newLine +
                        '# GET BINARY - DIRTY GET BINARY HACK' + newLine +
                        'curl -v -f http://jenkins:admin123@nexus-v2.cc-ci-cd.svc:8081/nexus/service/local/repositories/releases/content/com/viihde/war/0.0.1-${BUILD_TAG}/war-0.0.1-${BUILD_TAG}.war -o cc-fe.zip' + newLine +
                        'unzip cc-fe.zip -d cc-fe' + newLine +
                        '# DO OC BUILD STUFF WITH BINARY NOW' + newLine +
                        'NAME=' + appName  + newLine +
                        'oc patch bc ${NAME} -p "spec:' + newLine +
                        '   nodeSelector:' + newLine +
                        '   output:' + newLine +
                        '     to:' + newLine +
                        '       kind: ImageStreamTag' + newLine +
                        '       name: \'${NAME}:${JOB_NAME}.${BUILD_NUMBER}\'"' + newLine +
                        'oc start-build ${NAME} --from-dir=cc-fe/ --follow')
            }
        }
        publishers {
            downstreamParameterized {
                trigger(deployImageName) {
                    condition('SUCCESS')
                    parameters {
                        predefinedBuildParameters{
                            properties("BUILD_TAG=\${JOB_NAME}.\${BUILD_NUMBER}")
                            textParamValueOnNewLine(true)
                        }
                    }
                }
            }
            notifySlack(delegate)
        }
    }

    job(deployImageName) {
        description(jobDescription)
        parameters {
            string{
                name("BUILD_TAG")
                defaultValue("my-app-build.1234")
                description("The BUILD_TAG is the \${JOB_NAME}.\${BUILD_NUMBER} of the successful build to be promoted.")
            }
        }
        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)

        }
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'set -o xtrace' + newLine +
                        'PIPELINES_NAMESPACE=' + pipelineNamespace  + newLine +
                        'NAMESPACE=' + projectTestNamespace  + newLine +
                        'NAME=' + appName  + newLine +
                        'oc tag ${PIPELINES_NAMESPACE}/${NAME}:${BUILD_TAG} ${NAMESPACE}/${NAME}:${BUILD_TAG}' + newLine +
                        'oc project ${NAMESPACE}' + newLine +
                        'oc patch dc ${NAME} -p "spec:' + newLine +
                        '  template:' + newLine +
                        '    spec:' + newLine +
                        '      containers:' + newLine +
                        '        - name: ${NAME}' + newLine +
                        '          image: \'docker-registry.default.svc:5000/${NAMESPACE}/${NAME}:${BUILD_TAG}\'' + newLine +
                        '          env:' + newLine +
                        '            - name: NODE_ENV' + newLine +
                        '              value: \'production\'"' + newLine +
                        'oc rollout latest dc/${NAME}')
            }
            openShiftDeploymentVerifier {
                apiURL('')
                depCfg(appName)
                namespace(projectNamespace)
                // This optional field's value represents the number expected running pods for the deployment for the DeploymentConfig specified.
                replicaCount('1')
                authToken('')
                verbose('yes')
                // This flag is the toggle for turning on or off the verification that the specified replica count for the deployment has been reached.
                verifyReplicaCount('yes')
                waitTime('')
                waitUnit('sec')
            }
        }
        publishers {
            notifySlack(delegate)
        }
    }
}

buildMonitorView('cc-fe-monitor') {
    description('All build jobs for the react-js app')
    filterBuildQueue()
    filterExecutors()
    jobs {
        regex(".*cc.*")
    }
}
