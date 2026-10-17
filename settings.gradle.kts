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
    }
}
rootProject.name = "PramodAIVideoEditor"

include(":app")
include(":core-model")
include(":core-database")
include(":core-storage")
include(":core-security")
include(":feature-home")
include(":feature-projects")
include(":feature-editor")
include(":feature-captions")
include(":feature-typography")
include(":feature-templates")
include(":feature-audio")
include(":feature-vfx")
include(":feature-export")
include(":ai-groq")
include(":ai-gemini")
include(":media-engine")
