plugins {
    kotlin("jvm") version "2.4.0"
}

repositories { mavenCentral() }

tasks.register("diagnosticFailure") {
    doLast { error("sample failure") }
}

