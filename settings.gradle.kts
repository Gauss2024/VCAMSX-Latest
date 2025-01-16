pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven ("https://maven.pkg.github.com/GCX-HCI/tray" )
        maven(  "https://maven.aliyun.com/repository/google")
        maven(  "https://maven.aliyun.com/repository/gradle-plugin")
        maven(  "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "VCAMSX"
include(":app")
 