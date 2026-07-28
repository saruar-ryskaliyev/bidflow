plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

description = "Protobuf definitions and generated gRPC stubs for the auction serving API."

// Generated code cannot survive -Xlint:all -Werror, so this module skips the conventions
// plugin and sets the toolchain itself. Hand-written code lives in :serving.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(libs.grpc.api)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.protobuf.java)
    // @javax.annotation.Generated is emitted by protoc-gen-grpc-java; needed at compile time.
    compileOnly(libs.javax.annotation.api)
}

protobuf {
    protoc {
        // Coordinates match libs.versions.toml [versions] protobuf / grpc — kept as literals
        // because the catalog alias "protoc" collides with this extension's receiver.
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.82.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
