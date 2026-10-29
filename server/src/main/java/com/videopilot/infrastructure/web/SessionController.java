package com.videopilot.infrastructure.web;

import com.videopilot.identity.application.SessionApplicationService;
import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.security.IdentitySecurityProperties;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.LoginRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(M1ApiContracts.Paths.SESSIONS)
@ConditionalOnProperty(prefix = "videopilot.identity", name = "enabled", matchIfMissing = true)
public final class SessionController {

    private final SessionApplicationService sessions;
    private final IdentitySecurityProperties properties;

    public SessionController(SessionApplicationService sessions, IdentitySecurityProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @PostMapping
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        SessionApplicationService.LoginResult result = sessions.login(new SessionApplicationService.LoginCommand(
                request.username(),
                request.password(),
                httpRequest.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken(), properties.getSessionTtl()).toString())
                .body(toResponse(result.session()));
    }

    @GetMapping("/current")
    SessionResponse current(@AuthenticationPrincipal IdentityPrincipal principal) {
        return toResponse(principal.session());
    }

    @DeleteMapping("/current")
    ResponseEntity<Void> logout(@AuthenticationPrincipal IdentityPrincipal principal) {
        if (principal != null) {
            sessions.logout(principal.session().sessionId());
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.getCookieName(), value)
                .httpOnly(true)
                .secure(properties.isSecureCookie())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private SessionResponse toResponse(AuthenticatedSession session) {
        return new SessionResponse(
                session.ownerId().value(),
                session.username(),
                session.csrfToken(),
                session.expiresAt());
    }
}
