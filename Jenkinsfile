#!/usr/bin/env groovy

// =================================================================
// 🏗️ Komga (Gorse Integration) CI Pipeline
// Jenkins 运行在 ARM 上，通过 buildx 构建多架构镜像 (amd64+arm64)
// =================================================================

def commitAuthor = 'N/A'
def commitMsg = 'N/A'
def failedStage = 'N/A'
def failureReason = 'No failure reason captured'

def IMAGE_REPO = ''
def TAG_BUILD_NUMBER = ''
def IMAGE_WITH_TAG = ''
def LATEST_IMAGE_WITH_TAG = ''

pipeline {
    agent any

    environment {
        APP_NAME = 'komga'
        NEXUS_DOCKER_REGISTRY = 'docker.nexus.ixuni.win'
        NEXUS_CREDENTIALS_ID = 'docker-nexus-xuni'
        NOTIFICATION_SCRIPT = '/ntfy.sh'
        // 覆盖系统的 DOCKER_DEFAULT_PLATFORM，因为我们用 buildx 多架构构建
        DOCKER_DEFAULT_PLATFORM = ''
    }

    parameters {
        booleanParam(name: 'FORCE_BUILD', defaultValue: false, description: '强制构建，忽略 Docker 缓存')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds(abortPrevious: true)
    }

    triggers {
        githubPush()
    }

    stages {
        // =================================================================
        //  阶段一：环境准备与代码检出
        // =================================================================
        stage('环境信息与检出') {
            steps {
                script {
                    try {
                        IMAGE_REPO = "${env.NEXUS_DOCKER_REGISTRY}/${env.APP_NAME}".toLowerCase()
                        TAG_BUILD_NUMBER = env.BUILD_NUMBER
                        IMAGE_WITH_TAG = "${IMAGE_REPO}:${TAG_BUILD_NUMBER}"
                        LATEST_IMAGE_WITH_TAG = "${IMAGE_REPO}:latest"

                        echo "=== 🏗️ 构建信息 ==="
                        echo "项目: ${env.APP_NAME}"
                        echo "镜像: ${IMAGE_WITH_TAG}"

                        def authorOutput = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
                        def msgOutput = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
                        if (authorOutput) commitAuthor = authorOutput
                        if (msgOutput) commitMsg = msgOutput

                        echo "提交人: ${commitAuthor} | 信息: ${commitMsg}"
                    } catch (e) {
                        failedStage = env.STAGE_NAME
                        failureReason = e.message
                        throw e
                    }
                }
            }
        }

        // =================================================================
        //  阶段二：Gradle 构建 (WebUI + Spring Boot JAR)
        // =================================================================
        stage('Gradle 构建') {
            steps {
                script {
                    try {
                        echo "☕ 开始 Gradle 构建 (WebUI + bootJar)..."
                        sh '''
                            chmod +x ./gradlew
                            ./gradlew :komga:prepareThymeLeaf :komga:bootJar --no-daemon
                        '''
                        echo "✅ Gradle 构建成功"
                    } catch (e) {
                        failedStage = env.STAGE_NAME
                        failureReason = e.message
                        throw e
                    }
                }
            }
        }

        // =================================================================
        //  阶段三：多架构 Docker 镜像构建与推送 (buildx)
        // =================================================================
        stage('构建并推送多架构镜像') {
            steps {
                script {
                    try {
                        echo "🐳 开始多架构 Docker 构建 (amd64 + arm64)..."

                        // 登录 Nexus Docker Registry
                        withCredentials([usernamePassword(
                            credentialsId: "${NEXUS_CREDENTIALS_ID}",
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )]) {
                            sh "echo \$DOCKER_PASS | docker login ${NEXUS_DOCKER_REGISTRY} -u \$DOCKER_USER --password-stdin"
                        }

                        // 确保 QEMU 已注册（用于 ARM 上构建 amd64）
                        sh 'docker run --rm --privileged multiarch/qemu-user-static --reset -p yes || true'

                        // 创建/使用 buildx builder
                        sh '''
                            docker buildx create --name komga-builder --driver docker-container --platform linux/amd64,linux/arm64 --use 2>/dev/null || \
                            docker buildx use komga-builder
                            docker buildx inspect --bootstrap
                        '''

                        // 构建参数
                        def noCache = params.FORCE_BUILD ? '--no-cache' : ''

                        // 多架构构建并直接推送
                        sh """
                            docker buildx build \
                                --platform linux/amd64,linux/arm64 \
                                ${noCache} \
                                -t ${IMAGE_WITH_TAG} \
                                -t ${LATEST_IMAGE_WITH_TAG} \
                                --push \
                                -f Dockerfile .
                        """

                        echo "✅ 多架构镜像推送完成: ${IMAGE_WITH_TAG}"
                    } catch (e) {
                        failedStage = env.STAGE_NAME
                        failureReason = e.message
                        throw e
                    }
                }
            }
        }
    }

    // =================================================================
    //  构建后处理 (通知与清理)
    // =================================================================
    post {
        always {
            script {
                try {
                    if (IMAGE_REPO) {
                        echo "🧹 清理旧 Docker 镜像..."
                        sh """
                            docker images --format '{{.Repository}}:{{.Tag}}' | \
                            grep '^${IMAGE_REPO}:' | \
                            xargs -r docker rmi || true
                        """
                    }
                } catch (e) { echo "⚠️ 清理镜像警告: ${e.message}" }
            }
        }
        success {
            script {
                def msg = """🎉 构建推送成功！
📦 项目: ${env.APP_NAME}
👤 提交人: ${commitAuthor}
💬 信息: ${commitMsg}
🐳 镜像: ${IMAGE_WITH_TAG}
🏗️ 架构: amd64 + arm64
🔢 构建号: ${env.BUILD_NUMBER}
⏱️  耗时: ${currentBuild.durationString}
🔗 链接: ${env.BUILD_URL}"""
                if (env.NOTIFICATION_SCRIPT) sh "${env.NOTIFICATION_SCRIPT} ml256-jenkins \"${msg}\""
            }
        }
        failure {
            script {
                def finalReason = failureReason.replaceAll('"', '\\\\"')
                def msg = """❌ Jenkins 构建失败
📦 项目: ${env.APP_NAME}
👤 提交人: ${commitAuthor}
💥 阶段: ${failedStage}
📝 原因: ${finalReason}
🔗 日志: ${env.BUILD_URL}console"""
                if (env.NOTIFICATION_SCRIPT) sh "${env.NOTIFICATION_SCRIPT} ml256-jenkins \"${msg}\""
            }
        }
        aborted {
            script {
                def msg = """⏹️ Jenkins 构建中止
📦 项目: ${env.APP_NAME}
👤 提交人: ${commitAuthor}
🔗 详情: ${env.BUILD_URL}"""
                if (env.NOTIFICATION_SCRIPT) sh "${env.NOTIFICATION_SCRIPT} ml256-jenkins \"${msg}\""
            }
        }
    }
}
