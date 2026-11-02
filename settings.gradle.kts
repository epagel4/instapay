pluginManagement { includeBuild("build-logic") }

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "instapay"

include(
    "messaging-contracts",
    "payment-service:payment-domain",
    "payment-service:payment-application",
    "payment-service:payment-adapters",
    "settlement-service:settlement-domain",
    "settlement-service:settlement-application",
    "settlement-service:settlement-adapters",
    "performance-tests",
)
