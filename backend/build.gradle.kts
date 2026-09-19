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
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

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
    systemProperty("qms.security.key-dir", layout.buildDirectory.dir("test-keys").get().asFile.absolutePath)
    // The scheduled numbering reset is driven by hand in tests; a clock-driven one would race with them.
    systemProperty("qms.numbering.scheduler.cron", "-")
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
