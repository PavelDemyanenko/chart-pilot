package com.chartpilot.bridge;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Quality gate BUILD-01: ws-транспорт не зависит от MCP-tools. */
@AnalyzeClasses(packages = "com.chartpilot.bridge", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_standard_streams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule packages_free_of_cycles = slices()
            .matching("com.chartpilot.bridge.(*)..").should().beFreeOfCycles();

    // session — доменное ядро: не зависит от транспорта и MCP-tools.
    // Матчеры полные: "..tools.." ловил бы ещё tools.jackson (Jackson 3).
    @ArchTest
    static final ArchRule session_does_not_depend_on_ws_or_tools = noClasses()
            .that().resideInAPackage("com.chartpilot.bridge.session..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.chartpilot.bridge.ws..", "com.chartpilot.bridge.tools..");
}