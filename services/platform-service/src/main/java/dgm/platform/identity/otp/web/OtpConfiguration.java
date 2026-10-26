package dgm.platform.identity.otp.web;

import dgm.kit.boot.SecretsDirectory;
import dgm.kit.tls.TlsMaterial;
import dgm.platform.identity.otp.client.HttpSmsClient;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.crypto.SecureCodeSource;
import dgm.platform.identity.otp.service.CodeSource;
import dgm.platform.identity.otp.service.DirectMailCodeSender;
import dgm.platform.identity.otp.service.EmailCodeSender;
import dgm.platform.identity.otp.service.OtpProperties;
import dgm.platform.identity.otp.service.SmsSender;
import dgm.platform.notification.api.DirectMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Подключение одноразовых кодов: секреты {@code platform_otp_pepper} (хеш кода) и {@code platform_audit_hmac} (хеши аудита), генератор кодов
 * клиент провайдера SMS и отправитель письма с кодом смены e-mail. Значения секретов читаются в модулях шифрования и клиентов, здесь передаются только ссылки на них. Адрес
 * провайдера в стенде это заглушка {@code external-stubs}, в проде адрес настоящего провайдера (ключ {@code dgm.sms.base-url}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpProperties.class)
public class OtpConfiguration {

    @Bean
    OtpCrypto otpCrypto(SecretsDirectory secrets) {
        return OtpCrypto.fromSecret(secrets.read("platform_otp_pepper"));
    }

    @Bean
    AuditHasher auditHasher(SecretsDirectory secrets) {
        return AuditHasher.fromSecret(secrets.read("platform_audit_hmac"));
    }

    @Bean
    CodeSource codeSource() {
        return new SecureCodeSource();
    }

    @Bean
    SmsSender smsSender(SecretsDirectory secrets, TlsMaterial tls, @Value("${dgm.sms.base-url:https://external-stubs:8443}") String baseUrl) {
        return new HttpSmsClient(baseUrl, () -> secrets.read("platform_sms_key"), tls.trustOnlyContext());
    }

    /** Письмо с кодом смены e-mail на новый адрес: напрямую провайдеру почты, минуя очередь уведомлений (Р-169). */
    @Bean
    EmailCodeSender emailCodeSender(DirectMail mail) {
        return new DirectMailCodeSender(mail);
    }
}
