package dgm.kit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** Проверяет, что ArchUnit подключён и правило модульности 5 (каркас не знает о сервисах) выполняется. */
class ArchitectureSmokeTest {

    @Test
    void kitDoesNotDependOnServices() {
        JavaClasses kit = new ClassFileImporter().importPackages("dgm.kit");
        noClasses().that().resideInAPackage("dgm.kit..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dgm.catalog..", "dgm.inventory..", "dgm.order..",
                        "dgm.payment..", "dgm.delivery..", "dgm.platform..", "dgm.gateway..")
                .check(kit);
    }
}
