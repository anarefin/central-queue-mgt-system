plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("org.owasp.dependencycheck") version "13.0.0"
}

group = "com.qms"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// NFR-SEC-051: fail the build on unpatched critical findings. Run in CI with `gradle dependencyCheckAnalyze`.
dependencyCheck {
    failBuildOnCVSS = 9.0f
    nvd {
        apiKey = System.getenv("NVD_API_KEY")
    }
}

// FR-OPS-020: migrations run as a separate step before the app starts.
// The container entrypoint calls `java -jar app.jar --qms.mode=migrate` (see MigrationRunner).
