package com.example.user.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.example.user", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesOrMessaging = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..messaging..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnWebLayer = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..", "org.springframework.web..");

    /** No dual writes: only the outbox relay talks to Kafka. */
    @ArchTest
    static final ArchRule onlyOutboxRelayUsesKafkaTemplate = noClasses()
            .that().resideOutsideOfPackage("..messaging.publisher..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");

    /** Event records are the public contract: they must not depend on entities or framework internals. */
    @ArchTest
    static final ArchRule eventContractsAreSelfContained = noClasses()
            .that().resideInAPackage("..messaging.event..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..model..", "..repository..", "..service..", "org.springframework..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
