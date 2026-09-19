package com.qms;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** Package boundaries for the bounded contexts (ADR-0010, ADR-0012) and the queue-engine embedding seam (ADR-0001). */
@AnalyzeClasses(packages = "com.qms", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] CONTEXTS = {
        "com.qms.configuration..",
        "com.qms.issuance..",
        "com.qms.queue..",
        "com.qms.appointment..",
        "com.qms.session..",
        "com.qms.notification..",
        "com.qms.reporting..",
        "com.qms.identity..",
        "com.qms.audit.."
    };

    /** The queue engine can later be embedded in a site edge node, so it may not know about web or transport types. */
    static ArchRule queueIsTransportFree(String queuePackage) {
        return noClasses()
                .that()
                .resideInAPackage(queuePackage)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.http..",
                        "org.springframework.messaging..",
                        "org.springframework.security.web..",
                        "jakarta.servlet..",
                        "jakarta.websocket..")
                .allowEmptyShould(true);
    }

    @ArchTest
    static final ArchRule queueEngineHasNoWebOrTransportDependency = queueIsTransportFree("com.qms.queue..");

    @ArchTest
    static final ArchRule platformDoesNotDependOnAnyBoundedContext = noClasses()
            .that()
            .resideInAPackage("com.qms.platform..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(CONTEXTS)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching("com.qms.(*)..").should().beFreeOfCycles();

    @Test
    void queueRuleActuallyDetectsAWebDependency() {
        // Guards against the rule passing only because the queue package is empty.
        var classes = new ClassFileImporter().importPackages("com.qms.fixtures.badqueue");
        assertThat(classes).isNotEmpty();

        assertThatThrownBy(() -> queueIsTransportFree("com.qms.fixtures.badqueue..").check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework.web");
    }
}
