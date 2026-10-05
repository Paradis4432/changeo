package ar.changeo.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LocalMailbox implements TokenDelivery {
    private final Path directory;

    LocalMailbox(@Value("${changeo.mailbox}") String directory) { this.directory = Path.of(directory); }

    @Override
    public boolean deliver(UUID accountId, String purpose, String token) {
        try {
            PrivateFiles.writeNew(directory.resolve(accountId + "-" + purpose + "-" + UUID.randomUUID() + ".token"),
                    token.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }
}
