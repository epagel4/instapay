import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

group = "com.instapay"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    // Only version constraints; no Spring jar lands on the classpath of pure modules.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked,deprecation"))
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
    }
}

spotbugs {
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

tasks.named("spotbugsTest") { enabled = false }

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
}
