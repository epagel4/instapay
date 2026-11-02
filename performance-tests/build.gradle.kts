// Load tests against a running stack (docker compose up). Not part of `build`:
//   ./gradlew :performance-tests:gatlingRun --simulation com.instapay.perf.InternalTransferSimulation
plugins {
    java
    alias(libs.plugins.gatling)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// Gatling's plugin forwards only its own JVM args; pass -D options from the command line through.
tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    systemProperties =
        System.getProperties().filterKeys { (it as String).startsWith("perf.") }
            .mapKeys { it.key as String }
}
