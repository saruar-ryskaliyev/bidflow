plugins {
    id("bidflow.java-conventions")
    application
}

description = "JMH microbenchmarks for the auction hot path and the budget serving path."

dependencies {
    implementation(project(":auction-core"))
    implementation(project(":budget"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

application {
    // JMH's own runner discovers the generated benchmark list on the classpath.
    mainClass = "org.openjdk.jmh.Main"
}
