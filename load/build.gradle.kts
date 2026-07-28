plugins {
    id("bidflow.java-conventions")
    application
}

description = "Open-loop gRPC load harness with coordinated-omission-free HDR histograms."

dependencies {
    implementation(project(":serving-proto"))
    implementation(libs.hdrhistogram)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    testImplementation(project(":serving"))
    testImplementation(libs.grpc.inprocess)
}

application {
    mainClass = "io.bidflow.load.OpenLoopLoadGenerator"
}
