pluginManagement {
    repositories {
        google()
        // mirror fallbacks — در شبکه‌های محدودشده mavenCentral گاهی 429 می‌دهد
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // mirror fallbacks — در شبکه‌های محدودشده mavenCentral گاهی 429 می‌دهد
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}
rootProject.name = "SleepMusic"
include(":app")
