# 在其他项目中使用已发布的 SDK

## 1. 配置 GitHub Packages 访问

在你的项目根目录创建或编辑 `local.properties` 文件（确保此文件不被提交到版本控制）：

```properties
github.username=你的GitHub用户名
github.token=你的GitHub Personal Access Token
```

## 2. 在 Gradle 配置中添加仓库

### 方法 A: 在 `settings.gradle.kts` 中配置（推荐）

```kotlin
import java.util.Properties

// 加载 local.properties
val localProperties = File(rootProject.projectDir, "local.properties")
val githubUsername: String
val githubToken: String

if (localProperties.exists()) {
    val properties = Properties()
    properties.load(localProperties.inputStream())
    githubUsername = properties.getProperty("github.username") ?: ""
    githubToken = properties.getProperty("github.token") ?: ""
} else {
    githubUsername = ""
    githubToken = ""
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/abraham0929/sdk-kmp")
            credentials {
                username = githubUsername
                password = githubToken
            }
        }
    }
}
```

### 方法 B: 在 `build.gradle.kts` 中配置

```kotlin
import java.util.Properties

// 加载 local.properties
val localProperties = File(rootProject.projectDir, "local.properties")
val githubUsername: String
val githubToken: String

if (localProperties.exists()) {
    val properties = Properties()
    properties.load(localProperties.inputStream())
    githubUsername = properties.getProperty("github.username") ?: ""
    githubToken = properties.getProperty("github.token") ?: ""
} else {
    githubUsername = ""
    githubToken = ""
}

repositories {
    mavenCentral()
    google()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/abraham0929/sdk-kmp")
        credentials {
            username = githubUsername
            password = githubToken
        }
    }
}
```

## 3. 添加依赖

在你的模块的 `build.gradle.kts` 中添加依赖：

### For Kotlin Multiplatform Project

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.hyperledger.identus:sdk:3.0.0-fork.5")
            }
        }
    }
}
```

### For Android Project

```kotlin
dependencies {
    implementation("org.hyperledger.identus:sdk-android-release:3.0.0-fork.5")
    // 或者使用 debug 版本
    // implementation("org.hyperledger.identus:sdk-android-debug:3.0.0-fork.5")
}
```

### For JVM Project

```kotlin
dependencies {
    implementation("org.hyperledger.identus:sdk-jvm:3.0.0-fork.5")
}
```

## 4. 同步项目

运行 Gradle 同步：
```bash
./gradlew --refresh-dependencies
```

## 5. 创建 GitHub Personal Access Token

如果你还没有 GitHub Personal Access Token：

1. 访问 GitHub Settings > Developer settings > Personal access tokens > Tokens (classic)
2. 点击 "Generate new token" > "Generate new token (classic)"
3. 给 token 命名，例如 "Maven Package Read"
4. 选择权限：勾选 `read:packages`
5. 点击 "Generate token"
6. 复制生成的 token 并保存到 `local.properties`

## 6. 可用的版本

当前已发布版本：
- `3.0.0-fork.5` (最新)

你可以在 GitHub 仓库的 Packages 页面查看所有可用版本：
https://github.com/abraham0929/sdk-kmp/packages

## 7. 发布新版本

当你需要发布新版本时：

1. 更新 `gradle.properties` 中的版本号：
   ```properties
   version = 3.0.0-fork.6
   ```

2. 运行发布命令：
   ```bash
   ./gradlew :sdk:publishAllPublicationsToGitHubPackagesRepository
   ```

## 注意事项

- GitHub Packages 不允许覆盖已发布的版本，每次发布都需要更新版本号
- Personal Access Token 应该保密，不要提交到版本控制系统
- 确保 `local.properties` 已添加到 `.gitignore`
- 读取 GitHub Packages 需要有仓库的访问权限
