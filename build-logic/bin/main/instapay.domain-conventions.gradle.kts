plugins { id("instapay.java-conventions") }

tasks.jacocoTestCoverageVerification {
    violationRules { rule { limit { minimum = "0.85".toBigDecimal() } } }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
