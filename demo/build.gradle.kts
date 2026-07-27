plugins {
    id("bidflow.java-conventions")
    application
}

description = "One-process browser demo wiring the auction engine to leased budget enforcement."

dependencies {
    implementation(project(":auction-core"))
    implementation(project(":budget"))
}

application {
    mainClass = "io.bidflow.demo.DemoServer"
}
