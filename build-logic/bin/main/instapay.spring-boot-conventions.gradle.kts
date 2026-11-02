plugins {
    id("instapay.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
}

// Only the executable jar; keeps the Docker build copy step unambiguous.
tasks.jar { enabled = false }
