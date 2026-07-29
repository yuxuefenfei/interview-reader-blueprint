pipeline {
    agent {
        label 'ireader-builder'
    }

    tools {
        jdk 'jdk21'
        nodejs 'node20'
    }

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
    }

    environment {
        INCOMING_DIR = '/opt/ireader/incoming'
        STABLE_ARTIFACT = 'dist/interview-reader.jar'
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                sh '''
                    set -Eeuo pipefail
                    git rev-parse HEAD
                    git log -1 --pretty=fuller
                '''
            }
        }

        stage('Toolchain Check') {
            steps {
                sh '''
                    set -Eeuo pipefail
                    java -version
                    javac -version
                    node --version
                    npm --version
                    chmod +x ./mvnw
                    ./mvnw --version
                '''
            }
        }

        stage('Frontend Install') {
            steps {
                dir('frontend') {
                    sh '''
                        set -Eeuo pipefail
                        npm ci
                    '''
                }
            }
        }

        stage('Frontend Verify') {
            steps {
                dir('frontend') {
                    sh '''
                        set -Eeuo pipefail
                        npm run contract:check
                        npm test
                        npm run build
                    '''
                }
            }
        }

        stage('Backend Test') {
            steps {
                sh '''
                    set -Eeuo pipefail
                    ./mvnw \
                        --batch-mode \
                        --no-transfer-progress \
                        -DskipFrontend=true \
                        test
                '''
            }
        }

        stage('Package') {
            steps {
                sh '''
                    set -Eeuo pipefail
                    ./mvnw \
                        --batch-mode \
                        --no-transfer-progress \
                        -DskipFrontend=true \
                        -DskipTests \
                        package

                    mkdir -p dist
                    mapfile -t JARS < <(
                        find target \
                            -maxdepth 1 \
                            -type f \
                            -name 'interview-reader-*.jar' \
                            ! -name '*.jar.original' \
                            -print
                    )

                    if [[ "${#JARS[@]}" -ne 1 ]]; then
                        echo "预期恰好生成一个可执行 JAR，实际为 ${#JARS[@]}" >&2
                        printf '%s\n' "${JARS[@]}" >&2
                        exit 1
                    fi

                    install -m 0644 "${JARS[0]}" "$STABLE_ARTIFACT"
                    jar tf "$STABLE_ARTIFACT" | grep -q '^BOOT-INF/classes/'
                    unzip -p "$STABLE_ARTIFACT" META-INF/MANIFEST.MF | grep -q '^Start-Class:'
                    sha256sum "$STABLE_ARTIFACT" | tee "${STABLE_ARTIFACT}.sha256"
                '''
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts(
                    artifacts: 'dist/interview-reader.jar,dist/interview-reader.jar.sha256',
                    fingerprint: true,
                    onlyIfSuccessful: true
                )
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                    set -Eeuo pipefail
                    test -f "$STABLE_ARTIFACT"
                    test -d "$INCOMING_DIR"

                    SAFE_BUILD_TAG="$(printf '%s' "$BUILD_TAG" | tr -c 'A-Za-z0-9_.-' '_')"
                    TEMP_INCOMING="${INCOMING_DIR}/interview-reader.jar.tmp.${SAFE_BUILD_TAG}"

                    cleanup() {
                        rm -f -- "$TEMP_INCOMING"
                    }
                    trap cleanup EXIT

                    install -m 0600 "$STABLE_ARTIFACT" "$TEMP_INCOMING"
                    mv -f -- "$TEMP_INCOMING" "${INCOMING_DIR}/interview-reader.jar"
                    trap - EXIT

                    sha256sum "${INCOMING_DIR}/interview-reader.jar"
                    sudo -n /usr/local/sbin/deploy-interview-reader
                '''
            }
        }

        stage('Post Deploy Verify') {
            steps {
                sh '''
                    set -Eeuo pipefail
                    curl \
                        --fail \
                        --silent \
                        --show-error \
                        --max-time 5 \
                        --noproxy '*' \
                        http://127.0.0.1:28080/actuator/health/readiness
                    echo
                '''
            }
        }
    }

    post {
        always {
            junit(testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true)
        }
        success {
            echo "部署成功：${env.GIT_COMMIT}"
        }
        failure {
            echo '构建或部署失败，请检查失败 Stage 的日志。'
        }
    }
}
