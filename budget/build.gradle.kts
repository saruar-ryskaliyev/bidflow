plugins {
    id("bidflow.java-conventions")
}

description = "Distributed budget enforcement: a central authority granting spend authority to serving shards."

dependencies {
    // The simulator is a test dependency only. Production code knows nothing about how it
    // is driven, which is what allows the same classes to run under simulation and under a
    // real network without a behavioural difference between them.
    testImplementation(project(":sim"))
}

tasks.test {
    // The fault-injection sweep reports what it exercised. A green run that proves nothing
    // is indistinguishable from a green run that proves everything unless it says so.
    testLogging.showStandardStreams = true
}
