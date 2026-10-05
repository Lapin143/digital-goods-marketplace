package dgm.inventory;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import dgm.kit.arch.ServiceArchRules;

/** Архитектурные правила каркаса для сервиса (inventory-service): слои, часы, секреты, Outbox (c4-components.md, раздел 3). */
@AnalyzeClasses(packages = "dgm.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class InventoryArchitectureTest {

    @ArchTest
    static final ArchTests RULES = ArchTests.in(ServiceArchRules.class);
}
