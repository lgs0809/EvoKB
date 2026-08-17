package cn.lgs.knowledgebase;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private final JavaClasses imported =
      new ClassFileImporter().importPackages("cn.lgs.knowledgebase");

  @Test
  void domainMustNotDependOnOuterLayers() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..application..", "..infrastructure..", "..interfaces..", "org.springframework..")
        .check(imported);
  }

  @Test
  void applicationMustNotDependOnAdaptersOrWebFrameworks() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..infrastructure..",
            "..interfaces..",
            "org.springframework.web..",
            "org.springframework.jdbc..")
        .check(imported);
  }

  @Test
  void infrastructureMustNotDependOnInterfaces() {
    noClasses()
        .that()
        .resideInAPackage("..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..interfaces..")
        .check(imported);
  }

  @Test
  void interfacesMustNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("..interfaces..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .check(imported);
  }
}
