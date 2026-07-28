plugins {
    id("bidflow.java-conventions")
    application
}

description = "gRPC serving layer: auction over the wire with deadline propagation and load shedding."

dependencies {
    implementation(project(":auction-core"))
    implementation(project(":serving-proto"))
    implementation(project(":budget"))
    implementation(project(":ledger"))
    implementation(libs.grpc.netty.shaded)
    testImplementation(libs.grpc.inprocess)
}

application {
    mainClass = "io.bidflow.serving.AuctionServer"
}
