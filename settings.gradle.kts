// Root settings.gradle.kts for the standalone `moviesapp-server` project.
//
// This project was extracted from the multi-module `Movies-App` repo so it
// can be opened directly in IntelliJ as its own project. The companion
// Android `:app` repo lives at `../Movies-App/` next door; nothing here
// references it.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "moviesapp-server"
