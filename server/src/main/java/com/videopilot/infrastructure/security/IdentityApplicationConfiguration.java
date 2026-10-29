package com.videopilot.infrastructure.security;

import com.videopilot.identity.application.LocalAccountBootstrap;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.LoginAttemptGuard;
import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import com.videopilot.identity.port.IdentityPorts.SessionStore;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentitySecurityProperties.class)
@ConditionalOnProperty(prefix = "videopilot.identity", name = "enabled", matchIfMissing = true)
public class IdentityApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    SessionApplicationService sessionApplicationService(
            AccountStore accounts,
            SessionStore sessions,
            PasswordHash passwords,
            SecureToken tokens,
            LoginAttemptGuard loginAttempts,
            Clock clock,
            IdentitySecurityProperties properties) {
        return new SessionApplicationService(
                accounts,
                sessions,
                passwords,
                tokens,
                loginAttempts,
                clock,
                properties.getSessionTtl());
    }

    @Bean
    LocalAccountBootstrap localAccountBootstrap(AccountStore accounts, PasswordHash passwords) {
        return new LocalAccountBootstrap(accounts, passwords);
    }

    @Bean
    @ConditionalOnProperty(prefix = "videopilot.identity", name = "bootstrap-enabled", havingValue = "true")
    ApplicationRunner localIdentityBootstrapRunner(
            LocalAccountBootstrap bootstrap,
            IdentitySecurityProperties properties) {
        return arguments -> bootstrap.ensureAccount(
                properties.getBootstrapUsername(), properties.getBootstrapPassword());
    }
}
