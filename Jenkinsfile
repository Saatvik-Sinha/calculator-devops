pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 20, unit: 'MINUTES')
    }

    // Fires when the GitHub webhook (POST /github-webhook/) reports a push
    triggers {
        githubPush()
    }

    // Names must match Manage Jenkins -> Tools (Global Tool Configuration)
    tools {
        maven 'Maven-3.9'
        jdk 'JDK-17'
    }

    environment {
        // Jenkins "Username with password" credential (Docker Hub user + access token).
        // Exposes DOCKERHUB_CREDS_USR and DOCKERHUB_CREDS_PSW (masked in logs).
        DOCKERHUB_CREDS      = credentials('docker-hub-credentials')

        IMAGE_NAME           = 'calculator-devops'
        DOCKER_REPO          = "${DOCKERHUB_CREDS_USR}/${IMAGE_NAME}"
        IMAGE_TAG            = "${BUILD_NUMBER}"

        // Consumed by docker-compose.yml
        NGINX_HOST_PORT      = '8081'
        COMPOSE_PROJECT_NAME = 'calculator-devops'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                }
                echo "Building commit ${env.GIT_COMMIT_SHORT} on ${env.GIT_BRANCH ?: 'unknown branch'}"
            }
        }

        stage('Build') {
            steps {
                sh 'java -version'
                sh 'mvn -B -ntp clean package -DskipTests'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/calculator-devops.jar', fingerprint: true
                }
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B -ntp test'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
                }
            }
        }

        stage('Docker Build') {
            steps {
                sh '''
                    docker build --pull \
                        -t "${DOCKER_REPO}:${IMAGE_TAG}" \
                        -t "${DOCKER_REPO}:latest" \
                        .
                '''
            }
        }

        stage('Docker Push') {
            when {
                expression { isMainBranch() }
            }
            steps {
                sh '''
                    echo "${DOCKERHUB_CREDS_PSW}" | docker login -u "${DOCKERHUB_CREDS_USR}" --password-stdin
                    docker push "${DOCKER_REPO}:${IMAGE_TAG}"
                    docker push "${DOCKER_REPO}:latest"
                '''
            }
            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        stage('Deploy') {
            when {
                expression { isMainBranch() }
            }
            steps {
                sh '''
                    set -eu

                    # DOCKER_REPO, IMAGE_TAG, NGINX_HOST_PORT and COMPOSE_PROJECT_NAME
                    # are read by docker-compose.yml from the environment.
                    docker compose pull calculator-app
                    docker compose up -d --remove-orphans

                    echo "Waiting for the application to become healthy through nginx-proxy..."
                    for attempt in $(seq 1 30); do
                        if docker compose exec -T nginx-proxy wget -q -O - http://127.0.0.1/actuator/health | grep -q '"UP"'; then
                            echo "Deployment healthy: ${DOCKER_REPO}:${IMAGE_TAG} is serving traffic on host port ${NGINX_HOST_PORT}."
                            docker compose ps
                            exit 0
                        fi
                        sleep 2
                    done

                    echo "Deployment failed the health verification." >&2
                    docker compose ps
                    docker compose logs --tail=100
                    exit 1
                '''
            }
        }
    }

    // Final pipeline step: e-mail notification. Implemented in "post" so it also
    // runs when an earlier stage fails.
    post {
        success {
            sendNotification('SUCCESS')
        }
        failure {
            sendNotification('FAILURE')
        }
        unstable {
            sendNotification('UNSTABLE')
        }
        always {
            sh 'docker image prune -f || true'
        }
    }
}

/**
 * True when building the main branch. Works for a plain Pipeline job
 * (GIT_BRANCH = "origin/main") and for Multibranch jobs (BRANCH_NAME = "main").
 */
boolean isMainBranch() {
    String branch = env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'main'
    return branch == 'main' || branch.endsWith('/main')
}

/**
 * Sends the build result by e-mail using the Email Extension plugin.
 * Recipients: "Default Recipients" from Manage Jenkins -> System -> Extended E-mail Notification,
 * plus the user who started the build (if any).
 */
void sendNotification(String status) {
    String subject = "[Jenkins] ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${status}"
    String body = """
        <h2>Build ${status}</h2>
        <table cellpadding="4">
          <tr><td><b>Job</b></td><td>${env.JOB_NAME}</td></tr>
          <tr><td><b>Build</b></td><td>#${env.BUILD_NUMBER}</td></tr>
          <tr><td><b>Status</b></td><td>${status}</td></tr>
          <tr><td><b>Branch</b></td><td>${env.GIT_BRANCH ?: 'n/a'}</td></tr>
          <tr><td><b>Commit</b></td><td>${env.GIT_COMMIT_SHORT ?: 'n/a'}</td></tr>
          <tr><td><b>Image</b></td><td>${env.DOCKER_REPO}:${env.IMAGE_TAG}</td></tr>
          <tr><td><b>Build URL</b></td><td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td></tr>
          <tr><td><b>Console</b></td><td><a href="${env.BUILD_URL}console">${env.BUILD_URL}console</a></td></tr>
        </table>
    """.stripIndent()

    emailext(
        subject: subject,
        body: body,
        mimeType: 'text/html',
        to: '$DEFAULT_RECIPIENTS',
        recipientProviders: [requestor()],
        attachLog: status != 'SUCCESS',
        compressLog: true
    )
}
