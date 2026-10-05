package dgm.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;
import dgm.kit.arch.ServiceArchRules;
import java.util.Set;

/**
 * Правила каркаса для шлюза и правило «тонкого шлюза» (ADR-021): у шлюза нет баз, Kafka и Outbox, в нём нет сервлетов (стек
 * реактивный), а обращаться к сервисам он может только как прокси через Spring Cloud Gateway, не собственными клиентами.
 */
@AnalyzeClasses(packages = "dgm.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class GatewayArchitectureTest {

    @ArchTest
    static final ArchTests RULES = ArchTests.in(ServiceArchRules.class);

    @ArchTest
    static final ArchRule gatewayHasNoDatabaseNoKafkaNoOutbox = noClasses().that().resideInAPackage("dgm.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..",
                    "org.apache.kafka..", "dgm.kit.outbox..", "dgm.kit.events..")
            .because("шлюз хранит только счётчики в Redis (ADR-021, раздел «Что делает шлюз»)")
            .allowEmptyShould(true);

    /** Классы каркаса, написанные под сервлеты: на классах пути шлюза их загружать нельзя (в jar шлюза нет Servlet API). */
    private static final DescribedPredicate<JavaClass> SERVLET_PARTS_OF_KIT = DescribedPredicate.describe("сервлетные классы каркаса",
            c -> Set.of("dgm.kit.security.JwtFilter", "dgm.kit.security.CallerFilter", "dgm.kit.trace.TraceFilter",
                    "dgm.kit.problem.ProblemAdvice", "dgm.kit.problem.ProblemWriter").contains(c.getFullName()));

    @ArchTest
    static final ArchRule gatewayIsReactiveNotServlet = noClasses().that().resideInAPackage("dgm.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web.servlet..")
            .because("стек шлюза реактивный (WebFlux), сервлетов в нём нет")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule gatewayDoesNotUseServletParts = noClasses().that().resideInAPackage("dgm.gateway..")
            .should().dependOnClassesThat(SERVLET_PARTS_OF_KIT)
            .because("фильтры каркаса сервлетные, у шлюза свой фильтр на WebFlux (EntryFilter)")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule gatewayDoesNotCallServicesItself = noClasses().that().resideInAPackage("dgm.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.reactive.function.client..",
                    "org.springframework.web.client..", "java.net.http..")
            .because("запросы к сервисам пересылает только Spring Cloud Gateway: свои вызовы шлюза (например, проверка API-ключа R2) добавляются осознанно")
            .allowEmptyShould(true);
}
