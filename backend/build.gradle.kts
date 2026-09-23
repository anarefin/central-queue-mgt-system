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
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // SMTP for the email notification channel (ticket 40, FR-INT-040): JavaMailSender only, configured from
    // qms.notification.email.* rather than Spring Boot's own spring.mail auto-configuration, the same
    // roll-your-own-properties convention qms.notification.web-push.* already uses for its own adapter.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // Report export formats (ticket 49, FR-RPT-003): XLSX (streaming SXSSF, so a large export stays in bounded
    // memory, NFR-PERF-006) and PDF. CSV needs no library (AuditCsv's own precedent).
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // Compile-time, not runtimeOnly: cross-node realtime fan-out (ticket 59, ADR-0010) talks to the driver's own
    // LISTEN/NOTIFY API (org.postgresql.PGConnection/PGNotification) directly, so the type must be on the classpath
    // when ClusterRealtimeFanout is compiled, not only when the app runs.
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Tests that boot the application must not write signing keys into the source tree.
tasks.withType<Test>().configureEach {
    // Default worker heap is too small once the IT suite accumulates ~70+ Spring contexts
    // in one forked JVM; without this the executor OOMs during shutdown after all tests pass.
    maxHeapSize = "3g"
    systemProperty("qms.security.key-dir", layout.buildDirectory.dir("test-keys").get().asFile.absolutePath)
    // The scheduled numbering reset is driven by hand in tests; a clock-driven one would race with them.
    systemProperty("qms.numbering.scheduler.cron", "-")
    // Report export files (ticket 49) land under build/, never the source tree, the same convention test-keys uses.
    systemProperty("qms.reporting.export.storage-dir", layout.buildDirectory.dir("test-report-exports").get().asFile.absolutePath)
}

// Unit and slice tests run without Docker; `*IT` classes use Testcontainers against real PostgreSQL.
// Only the executable Spring Boot jar is built; a second "-plain" jar would make the Docker COPY ambiguous.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    exclude("**/*IT.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs Testcontainers-backed integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

// NFR-SEC-051: fail the build on unpatched critical findings. Run in CI with `gradle dependencyCheckAnalyze`.
dependencyCheck {
    failBuildOnCVSS = 9.0f
    nvd {
        apiKey = System.getenv("NVD_API_KEY")
    }
}

// FR-OPS-020: migrations run as a separate step before the app starts:
// `java -jar app.jar --spring.profiles.active=migrate` applies them and exits (see application-migrate.yml).
