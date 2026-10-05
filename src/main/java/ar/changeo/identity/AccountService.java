package ar.changeo.identity;

import ar.changeo.identity.api.AccountId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final TokenDelivery delivery;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    AccountService(IdentityStore store, PasswordEncoder passwords, TokenDelivery delivery, Clock clock) {
        this.store = store; this.passwords = passwords; this.delivery = delivery; this.clock = clock;
    }

    @Transactional
    public Registration register(String contact, String password) {
        String normalized = normalize(contact);
        validatePassword(password);
        if (store.byContact(normalized).isPresent()) { throw new IdentityFailure("No se pudo registrar esta cuenta de prueba"); }
        Account account = new Account(); account.id = UUID.randomUUID(); account.contact = normalized;
        account.passwordHash = passwords.encode(password); store.persist(account);
        store.audit(account.id, account.id, "REGISTER");
        return new Registration(new AccountId(account.id), issue(account, TokenPurpose.CONTACT));
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> credentials(String contact) {
        return store.byContact(canonicalContact(contact)).map(a -> new Credentials(a.id, a.contact, a.passwordHash, a.securityEpoch));
    }

    @Transactional(readOnly = true)
    public long epoch(UUID id) { return store.account(id).securityEpoch; }

    @Transactional
    public boolean resend(UUID actor) {
        store.lockAccounts(List.of(actor));
        Account account = store.account(actor);
        if (account.contactVerified) { return true; }
        return issue(account, TokenPurpose.CONTACT);
    }

    @Transactional
    public void verifyContact(UUID actor, String raw) {
        consume(Optional.of(actor), TokenPurpose.CONTACT, raw, null);
    }

    @Transactional
    public void forgot(String contact) {
        store.byContact(canonicalContact(contact)).ifPresent(found -> {
            store.lockAccounts(List.of(found.id)); issue(store.account(found.id), TokenPurpose.RECOVERY);
        });
    }

    @Transactional
    public void reset(String raw, String password) {
        validatePassword(password); consume(Optional.empty(), TokenPurpose.RECOVERY, raw, password);
    }

    @Transactional
    public void preferences(UUID actor, boolean sensitive, boolean adult) {
        store.lockAccounts(List.of(actor));
        Account account = store.account(actor);
        if (adult && (store.age(actor) < 18 || !account.contactVerified || store.restricted(actor))) {
            throw new IdentityFailure("Preferencia adulta no disponible");
        }
        account.sensitivePreference = sensitive; account.adultPreference = adult;
        store.audit(actor, actor, "PREFERENCES");
    }

    private boolean issue(Account account, TokenPurpose purpose) {
        List<ContactToken> previous = store.queries().createQuery("select t from ContactToken t where t.accountId = :id and t.purpose = :purpose order by t.id", ContactToken.class)
                .setParameter("id", account.id).setParameter("purpose", purpose).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getResultList();
        previous.forEach(token -> token.consumed = true);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ContactToken token = new ContactToken(); token.id = UUID.randomUUID(); token.accountId = account.id;
        token.purpose = purpose; token.tokenHash = hash(raw);
        token.generation = purpose == TokenPurpose.CONTACT ? ++account.contactGeneration : ++account.recoveryGeneration;
        token.expiresAt = clock.instant().plusSeconds(purpose == TokenPurpose.CONTACT ? 1800 : 900);
        store.persist(token);
        boolean delivered = delivery.deliver(account.id, purpose.name(), raw);
        if (!delivered) { token.consumed = true; }
        store.audit(account.id, account.id, delivered ? "TOKEN_DELIVERED_" + purpose : "TOKEN_DELIVERY_PENDING_" + purpose);
        return delivered;
    }

    private void consume(Optional<UUID> owner, TokenPurpose purpose, String raw, String password) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) { throw new IdentityFailure("Código inválido o vencido"); }
        List<Object[]> lookup = store.queries().createQuery("select t.id, t.accountId from ContactToken t where t.tokenHash = :hash", Object[].class)
                .setParameter("hash", hash(raw)).getResultList();
        if (lookup.size() != 1) { throw new IdentityFailure("Código inválido o vencido"); }
        UUID accountId = (UUID) lookup.getFirst()[1];
        if (owner.isPresent() && !owner.orElseThrow().equals(accountId)) { throw new IdentityFailure("Código inválido o vencido"); }
        store.lockAccounts(List.of(accountId));
        ContactToken token = store.lock(ContactToken.class, (UUID) lookup.getFirst()[0]);
        Account account = store.account(accountId);
        long generation = purpose == TokenPurpose.CONTACT ? account.contactGeneration : account.recoveryGeneration;
        if (token.purpose != purpose || token.consumed || !token.expiresAt.isAfter(clock.instant()) || token.generation != generation) {
            throw new IdentityFailure("Código inválido o vencido");
        }
        token.consumed = true;
        if (purpose == TokenPurpose.CONTACT) { account.contactVerified = true; }
        else {
            account.passwordHash = passwords.encode(password); account.securityEpoch++; account.recoveryGeneration++;
        }
        store.audit(accountId, accountId, purpose == TokenPurpose.CONTACT ? "CONTACT_VERIFIED" : "PASSWORD_RESET");
    }

    public static String canonicalContact(String contact) {
        return contact == null ? "" : contact.trim().toLowerCase(Locale.ROOT);
    }

    static String normalize(String contact) {
        if (contact == null) { throw new IdentityFailure("Usar un correo sintético .test"); }
        String normalized = canonicalContact(contact);
        if (normalized.length() > 254 || !normalized.matches("[a-z0-9._+%-]+@[a-z0-9.-]+\\.test")) {
            throw new IdentityFailure("Usar un correo sintético .test");
        }
        return normalized;
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IdentityFailure("La contraseña debe tener entre 12 y 72 caracteres");
        }
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Registration(AccountId accountId, boolean delivered) {}
    public record Credentials(UUID id, String contact, String passwordHash, long epoch) {}
}
