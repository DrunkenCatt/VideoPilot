package com.videopilot.infrastructure.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.identity")
public class IdentitySecurityProperties {

    private Duration sessionTtl = Duration.ofHours(8);
    private String cookieName = "VP_SESSION";
    private boolean secureCookie;
    private int loginAttemptLimit = 10;
    private Duration loginAttemptWindow = Duration.ofMinutes(15);
    private boolean bootstrapEnabled;
    private String bootstrapUsername = "";
    private String bootstrapPassword = "";

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isSecureCookie() {
        return secureCookie;
    }

    public void setSecureCookie(boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    public int getLoginAttemptLimit() {
        return loginAttemptLimit;
    }

    public void setLoginAttemptLimit(int loginAttemptLimit) {
        this.loginAttemptLimit = loginAttemptLimit;
    }

    public Duration getLoginAttemptWindow() {
        return loginAttemptWindow;
    }

    public void setLoginAttemptWindow(Duration loginAttemptWindow) {
        this.loginAttemptWindow = loginAttemptWindow;
    }

    public boolean isBootstrapEnabled() {
        return bootstrapEnabled;
    }

    public void setBootstrapEnabled(boolean bootstrapEnabled) {
        this.bootstrapEnabled = bootstrapEnabled;
    }

    public String getBootstrapUsername() {
        return bootstrapUsername;
    }

    public void setBootstrapUsername(String bootstrapUsername) {
        this.bootstrapUsername = bootstrapUsername;
    }

    public String getBootstrapPassword() {
        return bootstrapPassword;
    }

    public void setBootstrapPassword(String bootstrapPassword) {
        this.bootstrapPassword = bootstrapPassword;
    }
}
