package dgm.kit.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import dgm.fixtures.BadHandler;
import dgm.fixtures.ClockUser;
import dgm.fixtures.ConsoleWriter;
import dgm.fixtures.DirectProducer;
import dgm.fixtures.JulLogger;
import dgm.fixtures.MutableState;
import dgm.fixtures.SecretReader;
import dgm.fixtures.SessionUser;
import dgm.fixtures.orders.client.TransactionalClient;
import dgm.fixtures.orders.controller.OrderController;
import dgm.fixtures.orders.domain.NetworkDomain;
import dgm.fixtures.orders.repository.ClientCallingRepository;
import dgm.fixtures.orders.service.GoodService;
import dgm.fixtures.orders.service.PeekingService;
import dgm.fixtures.payments.api.PaymentApi;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Каждое правило {@link ServiceArchRules} обязано отвергнуть заведомо плохой класс (иначе оно ничего не защищает) и пропустить хороший.
 * Классы-нарушители лежат в {@code dgm.fixtures} и в проверку каркаса и сервисов не входят.
 */
class ServiceArchRulesNegativeTest {

    /** Правило → класс, который оно должно отвергнуть, и часть сообщения о нарушении. */
    private static final Map<String, Object[]> CASES = Map.ofEntries(
            Map.entry("noDirectSystemClock", new Object[] {ClockUser.class, "ClockUser"}),
            Map.entry("secretValueRevealedOnlyWhereAllowed", new Object[] {SecretReader.class, "SecretReader"}),
            Map.entry("noStandardStreams", new Object[] {ConsoleWriter.class, "ConsoleWriter"}),
            Map.entry("noJavaUtilLogging", new Object[] {JulLogger.class, "JulLogger"}),
            Map.entry("noDirectKafkaProducer", new Object[] {DirectProducer.class, "DirectProducer"}),
            Map.entry("eventHandlersDoNotBypassConsumer", new Object[] {BadHandler.class, "BadHandler"}),
            Map.entry("transactionsNotInControllersClientsOrchestrators", new Object[] {OrderController.class, "OrderController"}),
            Map.entry("transactionalMethodsNotInControllersClientsOrchestrators", new Object[] {TransactionalClient.class, "TransactionalClient"}),
            Map.entry("controllersDoNotUseRepositoriesOrClients", new Object[] {OrderController.class, "OrderController"}),
            Map.entry("repositoriesDoNotCallClients", new Object[] {ClientCallingRepository.class, "ClientCallingRepository"}),
            Map.entry("domainMakesNoNetworkCalls", new Object[] {NetworkDomain.class, "NetworkDomain"}),
            Map.entry("modulesAreReachedOnlyThroughApi", new Object[] {PeekingService.class, "PeekingService"}),
            Map.entry("noHttpSessions", new Object[] {SessionUser.class, "SessionUser"}),
            Map.entry("noMutableStaticFields", new Object[] {MutableState.class, "MutableState"}));

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    private static ArchRule rule(String name) throws ReflectiveOperationException {
        Field field = ServiceArchRules.class.getField(name);
        return (ArchRule) field.get(null);
    }

    static Stream<Arguments> negativeCases() {
        return CASES.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }

    @ParameterizedTest(name = "правило {0} отвергает {2}")
    @MethodSource("negativeCases")
    void ruleRejectsViolator(String ruleName, Class<?> violator, String expectedInMessage) throws ReflectiveOperationException {
        ArchRule rule = rule(ruleName);
        AssertionError error = assertThrows(AssertionError.class, () -> rule.check(classes(violator)));
        assertTrue(error.getMessage().contains(expectedInMessage), "в сообщении должен быть нарушитель " + expectedInMessage + ": " + error.getMessage());
    }

    @Test
    void everyRuleHasANegativeCase() {
        Set<String> declared = new TreeSet<>();
        for (Field f : ServiceArchRules.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && ArchRule.class.isAssignableFrom(f.getType())) {
                declared.add(f.getName());
            }
        }
        assertEquals(declared, new TreeSet<>(CASES.keySet()), "у каждого правила должен быть пример нарушения");
    }

    @Test
    void goodServicePassesEveryRule() throws ReflectiveOperationException {
        JavaClasses good = classes(GoodService.class, PaymentApi.class);
        for (String name : CASES.keySet()) {
            rule(name).check(good);
        }
    }
}
