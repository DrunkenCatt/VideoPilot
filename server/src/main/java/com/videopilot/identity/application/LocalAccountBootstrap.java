package com.videopilot.identity.application;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.domain.Username;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import java.util.UUID;

public final class LocalAccountBootstrap {

    private final AccountStore accounts;
    private final PasswordHash passwords;

    public LocalAccountBootstrap(AccountStore accounts, PasswordHash passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void ensureAccount(String rawUsername, String rawPassword) {
        String username = Username.normalize(rawUsername);
        if (username.isBlank() || rawPassword == null || rawPassword.length() < 12) {
            throw new IllegalArgumentException("Bootstrap username and password of at least 12 characters are required");
        }
        if (accounts.findByNormalizedUsername(username).isEmpty()) {
            accounts.create(new UserAccount(
                    new OwnerId(UUID.randomUUID()), username, passwords.encode(rawPassword), true));
        }
    }
}
