package dgm.platform.identity.web;

import dgm.platform.identity.service.AccountService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля {@code identity}: фильтр создания записи и расписание сверки.
 *
 * <p>Порядок фильтров каркаса: токен и маршрут (+10), здесь (+12), предел тела (+15). Запись создаётся до чтения тела запроса и до ключа
 * идемпотентности, но после проверки прав.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdentityConfiguration {

    @Bean
    FilterRegistrationBean<AccountProvisioningFilter> accountProvisioningFilterRegistration(AccountService accounts) {
        FilterRegistrationBean<AccountProvisioningFilter> registration = new FilterRegistrationBean<>(new AccountProvisioningFilter(accounts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 12);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
