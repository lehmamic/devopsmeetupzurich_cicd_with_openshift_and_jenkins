String label = "worker-${UUID.randomUUID().toString()}"

podTemplate(label: label,
        cloud: "openshift",
        containers: [
                containerTemplate(
                        name: "my-maven",
                        image: "docker-registry.default.svc:5000/myopenshiftnamespace/jenkins-slave-maven-3-5-rhel7",
                        alwaysPullImage: true,
                        resourceRequestMemory: "2Gi",
                        resourceRequestCpu: "500m",
                        resourceLimitMemory: "2Gi",
                        resourceLimitCpu: "1",
                        command: "cat",
                        ttyEnabled: true,
                        envVars: []),
        ],
        volumes: [
            persistentVolumeClaim(claimName: 'jenkins-maven-cache', mountPath: '/home/jenkins/.m2/repository'),
        ]
) {
    try {
        node(label) {
            timeout(time: 60, unit: 'MINUTES') {

                stage("checkout") {
                    scmVars = gitClone()
                    stash name: "osobj", includes: "openshift/**/*"
                }

                stage("version info") {
                    versionJson = readJSON file: 'version.json'

                    echo "Api version from json: ${versionJson.apiVersion}"
                }

                stage('build and test artefacts') {
                    container('my-maven') {
                        stage("build") {
                            try {
                                sh "source /usr/local/bin/scl_enable && mvn -T 1C -s maven.settings.xml clean package -e -B -pl my-api --also-make"
                            } finally {
                                junit '**/target/surefire-reports/*.xml'
                            }
                            zip zipFile: "my-api-${versionJson.apiVersion}.zip", archive: true, dir: "by-api/target", glob: "*.jar"
                        }

                        stage('stash: my-api') {
                            stash name: "api", includes: "**/target/*.jar"
                        }
                    }
                }

                stage('start backend') {
                    sh "java -jar my-api/target/my-api-${versionJson.apiVersion}.jar --spring.profiles.active=local &"
                    sh "java -jar my-mock/target/my-mock-${versionJson.apiVersion}-exec.jar --spring.profiles.active=local &"
                }

                stage('wait for server') {
                    container('my-maven') {
                        sh "./build-scripts/e2e-liveness-poll.sh http://localhost:18080/api/v1/config/version 5 20"
                        sh "./build-scripts/e2e-liveness-poll.sh http://localhost:18090/my-mock/system/status 5 20"
                    }
                }

                stage('integration tests') {
                    container('my-maven') {
                        stage('test: newman') {
                            try {
                                sh "cd ./postman-suites && newman run My_Api_Integration_Test.postman_collection.json -e Local.postman_environment.json -r junit --reporter-junit-export result.xml"
                            } finally {
                                junit '**/postman-suites/*.xml'
                            }
                        }
                    }
                }
            }
        }

        // execute on the master node (os tools installed)
        node {
            stage("build docker") {
                stage("docker: my-api") {
                    unstash "osobj"
                    unstash "api"

                    sh "oc process -f openshift/build-config/my-api-docker.template.yml -p DOCKER_IMAGE_TAG=${versionJson.apiVersion} | oc apply --force -f -"
                    sh "oc start-build my-api-docker --from-archive=my-api/target/my-api-${versionJson.apiVersion}.jar -n jenkinsnamespace --follow --wait"
                }
            }

            if (scmVars.BRANCH_NAME.equals("master") || scmVars.BRANCH_NAME.equals("develop")) {
                stage("deploy dev"){
                    openshift.withCluster(environment.cluster) {
                        openshift.withProject(environment.namespace) {
                            // apply kubernetes object
                            def objects = openshift.process("-f", "openshift/my-api/my-api.template.yml","--param-file", "openshift/my-api/my-api.dev.params","-p", "DOCKER_IMAGE_TAG=${dockerImageTag}", "-p", "KUBERNETES_NAMESPACE=devkubernetesnamespace")
                            openshift.apply(objects, "--force")

                            // do a rolling deployment
                            def rm = openshift.selector('dc', "my-api-dc").rollout()
                            rm.latest()
                            // Wait and print status
                            rm.status()
                        }
                    }
                }
            }
        }

        currentBuild.result = 'SUCCESS'
    } catch (e) {
        echo "Error occured while executing pipeline: ${e}"
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        node(label) {
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients:  emailextrecipients([[$class: 'DevelopersRecipientProvider'], [$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']])])
        }
    }
}
