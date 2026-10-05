package ar.changeo.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorService {
    private final IdentityStore store;
    private final FactorCipher cipher;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    OperatorService(IdentityStore store, FactorCipher cipher, PasswordEncoder passwords) {
        this.store = store; this.cipher = cipher; this.passwords = passwords;
    }

    @Transactional
    public Path provision(String contact, Set<String> roleNames, Path enrollment) {
        Set<AdminRole> roles = new HashSet<>();
        try { roleNames.forEach(role -> roles.add(AdminRole.valueOf(role))); }
        catch (IllegalArgumentException failure) { throw new IdentityFailure("Rol de operador inválido"); }
        if (roles.isEmpty()) { throw new IdentityFailure("Seleccionar roles mínimos explícitos"); }
        boolean factors = !store.queries().createQuery("select m.accountId from AdminMfa m", UUID.class).setMaxResults(1).getResultList().isEmpty();
        cipher.initializeForOperator(factors);
        String normalized = AccountService.normalize(contact);
        Optional<Account> found = store.byContact(normalized);
        Account account;
        if (found.isPresent()) {
            store.lockAccounts(List.of(found.orElseThrow().id)); account = store.account(found.orElseThrow().id);
        } else {
            account = new Account(); account.id = UUID.randomUUID(); account.contact = normalized;
        }
        byte[] passwordBytes = new byte[24]; random.nextBytes(passwordBytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes);
        account.passwordHash = passwords.encode(password); account.roles = roles; account.securityEpoch++; account.recoveryGeneration++;
        if (found.isEmpty()) { store.persist(account); }
        byte[] secret = new byte[20]; random.nextBytes(secret);
        AdminMfa factor = store.queries().find(AdminMfa.class, account.id);
        if (factor == null) { factor = new AdminMfa(); factor.accountId = account.id; }
        factor.encryptedSecret = cipher.encrypt(secret); factor.lastCounter = -1;
        if (!store.queries().contains(factor)) { store.persist(factor); }
        String material = "SANDBOX_SYNTHETIC\naccount=" + account.id + "\ncontact=" + account.contact
                + "\npassword=" + password + "\ntotpBase32=" + base32(secret) + "\n";
        try { PrivateFiles.writeNew(enrollment, material.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failure) { throw new IdentityFailure("Archivo privado de inscripción no disponible; usar ruta nueva"); }
        store.audit(account.id, account.id, "LOCAL_OPERATOR_PROVISIONED");
        return enrollment;
    }

    private static String base32(byte[] input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder output = new StringBuilder(); int buffer = 0; int bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 255); bits += 8;
            while (bits >= 5) { bits -= 5; output.append(alphabet.charAt((buffer >> bits) & 31)); }
        }
        if (bits > 0) { output.append(alphabet.charAt((buffer << (5 - bits)) & 31)); }
        return output.toString();
    }
}
