package com.videopilot.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.application.IdentityException;
import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.infrastructure.error.ErrorCode;
import com.videopilot.infrastructure.trace.TraceContext;
import com.videopilot.infrastructure.web.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentitySecurityProperties.class)
@ConditionalOnProperty(prefix = "videopilot.identity", name = "enabled", matchIfMissing = true)
public class IdentitySecurityConfiguration {

    @Bean
    SecurityFilterChain identitySecurityFilterChain(
            HttpSecurity http,
            SessionApplicationService sessions,
            IdentitySecurityProperties properties,
            ObjectMapper objectMapper) throws Exception {
        SecurityErrorWriter errorWriter = new SecurityErrorWriter(objectMapper);
        SessionAuthenticationFilter authenticationFilter =
                new SessionAuthenticationFilter(sessions, properties.getCookieName(), errorWriter);
        SessionCsrfFilter csrfFilter = new SessionCsrfFilter(sessions, errorWriter);

        return http
                // CSRF is checked against the server-side session by SessionCsrfFilter.
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/sessions").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/sessions/current").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                response, ErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                response, ErrorCode.CSRF_REJECTED, HttpStatus.FORBIDDEN)))
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfFilter, SessionAuthenticationFilter.class)
                .build();
    }

    static final class SessionAuthenticationFilter extends OncePerRequestFilter {

        private final SessionApplicationService sessions;
        private final String cookieName;
        private final SecurityErrorWriter errorWriter;

        private SessionAuthenticationFilter(
                SessionApplicationService sessions,
                String cookieName,
                SecurityErrorWriter errorWriter) {
            this.sessions = sessions;
            this.cookieName = cookieName;
            this.errorWriter = errorWriter;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String rawToken = findCookie(request, cookieName);
            if (rawToken != null) {
                try {
                    IdentityPrincipal principal = new IdentityPrincipal(sessions.authenticate(rawToken));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                } catch (IdentityException exception) {
                    if (exception.reason() == IdentityException.Reason.AUTHENTICATION_REQUIRED) {
                        SecurityContextHolder.clearContext();
                    } else {
                        errorWriter.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
                        return;
                    }
                }
            }
            filterChain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        private static String findCookie(HttpServletRequest request, String name) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            return Arrays.stream(cookies)
                    .filter(cookie -> name.equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }

    static final class SessionCsrfFilter extends OncePerRequestFilter {

        private final SessionApplicationService sessions;
        private final SecurityErrorWriter errorWriter;

        private SessionCsrfFilter(SessionApplicationService sessions, SecurityErrorWriter errorWriter) {
            this.sessions = sessions;
            this.errorWriter = errorWriter;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (requiresCsrf(request) && currentPrincipal() instanceof IdentityPrincipal principal
                    && !sessions.verifyCsrf(principal.session(), request.getHeader("X-CSRF-Token"))) {
                errorWriter.write(response, ErrorCode.CSRF_REJECTED, HttpStatus.FORBIDDEN);
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean requiresCsrf(HttpServletRequest request) {
            String method = request.getMethod();
            boolean safe = HttpMethod.GET.matches(method)
                    || HttpMethod.HEAD.matches(method)
                    || HttpMethod.OPTIONS.matches(method);
            boolean login = HttpMethod.POST.matches(method) && "/api/v1/sessions".equals(request.getRequestURI());
            return !safe && !login;
        }

        private Object currentPrincipal() {
            return SecurityContextHolder.getContext().getAuthentication() == null
                    ? null
                    : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        }
    }

    static final class SecurityErrorWriter {

        private final ObjectMapper objectMapper;

        private SecurityErrorWriter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        private void write(HttpServletResponse response, ErrorCode code, HttpStatus status) throws IOException {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                    code.name(), code.publicMessage(), code.retryable(), MDC.get(TraceContext.REQUEST_ID)));
        }
    }
}
