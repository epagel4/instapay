package com.instapay.payment.adapters;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Enforces ADR-001. Rules allow empty packages until the layers are populated. */
@AnalyzeClasses(packages = "com.instapay", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

  private static final String DOMAIN = "com.instapay.payment.domain..";
  private static final String APPLICATION = "com.instapay.payment.application..";
  private static final String ADAPTERS = "com.instapay.payment.adapters..";

  @ArchTest
  static final ArchRule layers_respect_hexagonal_dependencies =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .withOptionalLayers(true)
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Adapters")
          .definedBy(ADAPTERS)
          .whereLayer("Adapters")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Adapters")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Adapters");

  @ArchTest
  static final ArchRule core_is_framework_free =
      noClasses()
          .that()
          .resideInAnyPackage(DOMAIN, APPLICATION)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "org.hibernate..",
              "com.fasterxml.jackson..",
              "org.apache.kafka..",
              "com.instapay.contracts..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule web_does_not_touch_persistence =
      noClasses()
          .that()
          .resideInAPackage("com.instapay.payment.adapters.web..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.instapay.payment.adapters.persistence..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule domain_never_uses_floating_point_money =
      fields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage(DOMAIN)
          .should()
          .notHaveRawType(double.class)
          .andShould()
          .notHaveRawType(float.class)
          .andShould()
          .notHaveRawType(Double.class)
          .andShould()
          .notHaveRawType(Float.class)
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule core_reads_time_only_from_injected_clock =
      noClasses()
          .that()
          .resideInAnyPackage(DOMAIN, APPLICATION)
          .should()
          .callMethod(Instant.class, "now")
          .orShould()
          .callMethod(LocalDateTime.class, "now")
          .orShould()
          .callMethod(LocalDate.class, "now")
          .allowEmptyShould(true);
}
