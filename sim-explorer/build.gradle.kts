plugins {
    id("bidflow.java-conventions")
    application
}

description = "Interactive deterministic browser explorer for multi-shard budget simulation."

dependencies {
    implementation(project(":budget-sim"))
    implementation(project(":sim"))
    implementation(project(":budget"))
    implementation(project(":auction-core"))
}

application {
    mainClass = "io.bidflow.explorer.ExplorerServer"
}
