package com.example.order.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.kafka.annotation.KafkaListener;

@AnalyzeClasses(packages = "com.example.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotUseRepositoriesOrMessaging = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..messaging..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnWebLayer = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..", "org.springframework.web..");

    /** The domain model knows nothing about the API, persistence access, or messaging. */
    @ArchTest
    static final ArchRule modelIsIndependent = noClasses()
            .that().resideInAPackage("..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..", "..dto..", "..service..", "..repository..", "..messaging..",
                    "org.springframework..");

    /** No dual writes: only the outbox relay sends events to Kafka (the DLT recoverer uses KafkaOperations). */
    @ArchTest
    static final ArchRule onlyOutboxRelayUsesKafkaTemplate = noClasses()
            .that().resideOutsideOfPackage("..messaging.publisher..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");

    /** Listeners are thin adapters in one place; business rules stay in services. */
    @ArchTest
    static final ArchRule listenersLiveInConsumerPackage = methods()
            .that().areAnnotatedWith(KafkaListener.class)
            .should().beDeclaredInClassesThat().resideInAPackage("..messaging.consumer..");

    /** Event records are the public contract: they must not depend on entities or framework internals. */
    @ArchTest
    static final ArchRule eventContractsAreSelfContained = noClasses()
            .that().resideInAPackage("..messaging.event..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..model..", "..repository..", "..service..", "org.springframework..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
