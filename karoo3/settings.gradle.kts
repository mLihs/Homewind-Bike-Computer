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
        mavenCentral()
        // karoo-ext without GitHub login: clone hammerheadnav/karoo-ext at tag 1.1.9 and run
        // `./gradlew :lib:publishToMavenLocal` there once.
        mavenLocal {
            content { includeGroup("io.hammerhead") }
        }
        // karoo-ext from GitHub Packages: needs a GitHub token with read:packages
        // (gpr.user / gpr.key in ~/.gradle/gradle.properties).
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            content { includeGroup("io.hammerhead") }
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("").get()
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("").get()
            }
        }
    }
}

rootProject.name = "homewind-karoo"
include("app")
