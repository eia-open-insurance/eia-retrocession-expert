rootProject.name = "eia-retrocession-expert"

includeBuild("../eia-platform") {
    dependencySubstitution {
        substitute(module("com.eia:libs")).using(project(":libs"))
    }
}
