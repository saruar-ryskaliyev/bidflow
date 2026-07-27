rootProject.name = "bidflow"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("auction-core")
include("sim")
include("budget")
include("demo")
