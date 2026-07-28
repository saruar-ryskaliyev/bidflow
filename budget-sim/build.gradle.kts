plugins {
    id("bidflow.java-conventions")
}

description = "Deterministic multi-shard budget scenarios for simulation and the explorer."

dependencies {
    implementation(project(":sim"))
    implementation(project(":budget"))
    implementation(project(":auction-core"))
}
