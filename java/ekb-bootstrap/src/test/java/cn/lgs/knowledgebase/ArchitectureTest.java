package cn.lgs.knowledgebase;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private final JavaClasses imported = new ClassFileImporter().importPackages("cn.lgs.knowledgebase");

    @Test
    void domainMustNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..infrastructure..", "..interfaces..", "org.springframework..")
                .check(imported);
    }

    @Test
    void applicationMustNotDependOnAdapters() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..interfaces..")
                .check(imported);
    }
}
