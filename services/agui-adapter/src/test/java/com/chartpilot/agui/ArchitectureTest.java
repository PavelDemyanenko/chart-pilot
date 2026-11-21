package com.chartpilot.agui;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Quality gate BUILD-01: llm/tools не зависят от web-фасада. */
@AnalyzeClasses(packages = "com.chartpilot.agui", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_standard_streams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule packages_free_of_cycles = slices()
            .matching("com.chartpilot.agui.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule llm_and_tools_do_not_depend_on_web = noClasses()
            .that().resideInAnyPackage("..llm..", "..tools..")
            .should().dependOnClassesThat().resideInAPackage("..web..");
}