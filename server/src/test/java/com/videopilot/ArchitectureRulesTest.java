package com.videopilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.videopilot.analysis.port.JobTransitionStore;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.videopilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controllersLiveAtTheWebBoundary = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..infrastructure.web..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotAccessInfrastructureClients = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..mapper..", "..persistence..", "..messaging..", "..storage..", "..model..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule businessModulesDoNotDependOnInfrastructure = noClasses()
            .that().resideInAnyPackage(
                    "com.videopilot.identity..",
                    "com.videopilot.media..",
                    "com.videopilot.analysis..",
                    "com.videopilot.context..",
                    "com.videopilot.retrieval..",
                    "com.videopilot.answer..",
                    "com.videopilot.agent..",
                    "com.videopilot.feedback..",
                    "com.videopilot.evaluation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule m2ContextDoesNotDependOnRedisAsAStateStore = noClasses()
            .that().resideInAnyPackage(
                    "com.videopilot.context..",
                    "com.videopilot.infrastructure.context..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.data.redis..",
                    "org.redisson..");

    @ArchTest
    static final ArchRule boundaryCallersCannotBypassTheJobTransitionService = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .or().haveSimpleNameEndingWith("Consumer")
            .or().haveSimpleNameEndingWith("Mapper")
            .should().dependOnClassesThat().areAssignableTo(JobTransitionStore.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule jobMappersStayInsideThePersistenceAdapterPackage = classes()
            .that().haveSimpleNameEndingWith("Mapper")
            .and().resideInAPackage("..analysis..")
            .should().resideInAPackage("..infrastructure.analysis.persistence..");
}
