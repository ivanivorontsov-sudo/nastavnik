pluginManagement {
    repositories { maven("https://dl-ssl.google.com/dl/android/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven("https://dl-ssl.google.com/dl/android/maven2/"); mavenCentral() }
}
rootProject.name = "Nastavnik"
include(":app")
