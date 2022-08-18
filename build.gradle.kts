import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.plugins
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc

// bug in IntelliJ in which libs shows up as not being accessible
// see https://youtrack.jetbrains.com/issue/KTIJ-19369
@Suppress("DSL_SCOPE_VIOLATION")

plugins {
    base
    java
    alias(libs.plugins.protobufPlugin)
    alias(libs.plugins.execforkPlugin)

    kotlin("jvm") version "1.7.10"
}

group = "electionguard.remote"
version = "1.0-SNAPSHOT"
val pbandkVersion by extra("0.13.0")

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/danwallach/electionguard-kotlin-multiplatform")
        credentials {
            username = project.findProperty("github.user") as String? ?: System.getenv("USERNAME")
            password = project.findProperty("github.key") as String? ?: System.getenv("TOKEN")
        }
    }
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

dependencies {
    api(platform(libs.protobufBom))

    implementation(platform(libs.grpcBom))
    implementation(libs.grpcProtobuf)
    implementation(libs.grpcStub)
    compileOnly(libs.tomcatAnnotationsApi)
    implementation(libs.protobufJava)

    implementation(libs.bytesLib)
    implementation(libs.jcommander)

    implementation(libs.flogger)
    runtimeOnly(libs.floggerBackend)

    implementation("electionguard-kotlin-multiplatform:electionguard-kotlin-multiplatform-jvm:1.0-SNAPSHOT")
    implementation(kotlin("stdlib-common", "1.6.20"))
    implementation(kotlin("stdlib", "1.6.20"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")

    // Portable logging interface. On the JVM, we'll get "logback", which gives
    // us lots of features. On Native, it ultimately just prints to stdout.
    // On JS, it uses console.log, console.error, etc.
    implementation("io.github.microutils:kotlin-logging:2.1.21")

    // Logging implementation (used by "kotlin-logging").
    implementation("ch.qos.logback:logback-classic:1.3.0-alpha12")

    // A multiplatform Kotlin library for working with date and time.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.3.2")

    // A multiplatform Kotlin library for working with protobuf.
    implementation("pro.streem.pbandk:pbandk-runtime:$pbandkVersion")

    // A multiplatform Kotlin library for Result monads
    implementation("com.michael-bull.kotlin-result:kotlin-result:1.1.15")

    runtimeOnly(libs.grpcNettyShaded)

    testImplementation(libs.truth)
    testImplementation(libs.truthJava8Extension)
}

protobuf {
    protoc {
        // The artifact spec for the Protobuf Compiler
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
            }
        }
    }
}

// handle proto generated source and class files
sourceSets {
    main {
        java {
            srcDir("build/generated/source/proto/main/java")
            srcDir("build/generated/source/proto/main/grpc")
        }
    }
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks {
    register("fatJar", Jar::class.java) {
        archiveClassifier.set("all")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes("Main-Class" to "electionguard.viewer.ViewerMain")
        }
        from(configurations.runtimeClasspath.get()
            // .onEach { println("add from dependencies: ${it.name}") }
            .map { if (it.isDirectory) it else zipTree(it) })
        val sourcesMain = sourceSets.main.get()
        exclude("/META-INF/PFOPENSO.*")
        // sourcesMain.allSource.forEach { println("add from sources: ${it.name}") }
        from(sourcesMain.output)
    }
}