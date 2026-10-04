package dgm.kit.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/**
 * Сам каркас проходит общие правила сервисов. Анализируются только рабочие классы: тестовые фикстуры (помощники, правила) в проверку не входят,
 * они не попадают в сервисы.
 */
@AnalyzeClasses(packages = "dgm.kit", importOptions = {ImportOption.DoNotIncludeTests.class, KitArchitectureTest.ExcludeFixtures.class})
class KitArchitectureTest {

    /** Фикстуры лежат в другом каталоге сборки (testFixtures), стандартный параметр DoNotIncludeTests их не отсекает. */
    public static final class ExcludeFixtures implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("testFixtures") && !location.contains("test-fixtures");
        }
    }

    @ArchTest
    static final ArchTests SERVICE_RULES = ArchTests.in(ServiceArchRules.class);
}
