package ar.changeo.identity;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSecurityService implements InitializingBean {
    private final IdentityStore store;
    private final FactorCipher cipher;
    private final PasswordEncoder passwords;
    private final AbuseLimits limits;
    private final Clock clock;

    AdminSecurityService(IdentityStore store, FactorCipher cipher, PasswordEncoder passwords, AbuseLimits limits, Clock clock) {
        this.store = store; this.cipher = cipher; this.passwords = passwords; this.limits = limits; this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public void afterPropertiesSet() {
        store.queries().createQuery("select m from AdminMfa m", AdminMfa.class).getResultList()
                .forEach(factor -> cipher.decrypt(factor.encryptedSecret));
    }

    @Transactional
    public long verify(UUID actor, String code, String source) {
        if (!limits.allow("mfa-account:" + actor, 5) || !limits.allow("mfa-source:" + source, 5)) {
            throw new IdentityFailure("Demasiados intentos; reintentar en un minuto");
        }
        store.lockAccounts(List.of(actor));
        Account account = store.account(actor);
        if (account.roles.isEmpty() || store.restricted(actor)) { throw new IdentityFailure("MFA no disponible"); }
        AdminMfa factor = store.lock(AdminMfa.class, actor);
        long matched = Totp.match(cipher.decrypt(factor.encryptedSecret), code, clock.instant().getEpochSecond() / 30, factor.lastCounter);
        if (matched < 0) { throw new IdentityFailure("Código inválido, vencido o utilizado"); }
        factor.lastCounter = matched;
        store.audit(actor, actor, "MFA_VERIFIED");
        return account.securityEpoch;
    }

    @Transactional
    public long reauthenticate(UUID actor, String password, String source) {
        if (!limits.allow("reauth:" + actor + ":" + source, 5)) { throw new IdentityFailure("Demasiados intentos"); }
        store.lockAccounts(List.of(actor));
        Account account = store.account(actor);
        if (!passwords.matches(password, account.passwordHash)) { throw new IdentityFailure("No se pudo autenticar"); }
        return account.securityEpoch;
    }
}
