plugins {
    id("bidflow.java-conventions")
}

description = "Idempotent spend ledger: checksummed WAL and atomic snapshots for exactly-once click charging."

dependencies {
    implementation(project(":budget"))
}
