package com.videopilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
                    "..identity..", "..media..", "..analysis..", "..context..", "..retrieval..",
                    "..answer..", "..agent..", "..feedback..", "..evaluation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");
}
