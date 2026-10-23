package ar.changeo.moderation;

import ar.changeo.files.api.ReviewFileAccess;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
class ModerationPreparationConfiguration {
    @Bean PreparationSettings moderationPreparationSettings(Environment environment) {
        String mode=environment.getProperty("changeo.moderation-preparation","off");
        if(mode.equals("off")) { return new PreparationSettings(null,null); }
        if(!mode.equals("openai-wire") || !environment.matchesProfiles("sandbox")
                || !environment.getProperty("changeo.synthetic-evidence",Boolean.class,false)
                || environment.getProperty("changeo.real-money",Boolean.class,false)
                || environment.getProperty("changeo.synthetic-moderation",Boolean.class,false)) {
            throw new IllegalArgumentException("Preparation mode unavailable");
        }
        URI origin;
        try { origin=URI.create(environment.getRequiredProperty("changeo.moderation-preparation-origin")); }
        catch(IllegalArgumentException failure) { throw new IllegalArgumentException("Preparation origin invalid"); }
        OpenAiReviewClient.validateOrigin(origin);
        Path credential;
        try { credential=Path.of(environment.getRequiredProperty("changeo.moderation-preparation-credential")).toAbsolutePath().normalize(); }
        catch(IllegalArgumentException failure) { throw new IllegalArgumentException("Preparation credential unavailable"); }
        Path root=Path.of(".runtime/external-moderation-preparation-20261020").toAbsolutePath().normalize();
        if(!credential.startsWith(root) || credential.equals(root)) { throw new IllegalArgumentException("Preparation credential unavailable"); }
        return new PreparationSettings(origin,credential);
    }

    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="changeo.moderation-preparation",havingValue="openai-wire")
    OpenAiReviewClient openAiReviewClient(PreparationSettings settings) {
        return new OpenAiReviewClient(settings.origin(),readCredential(settings.credential()));
    }

    @Bean
    @ConditionalOnProperty(name="changeo.moderation-preparation",havingValue="openai-wire")
    ModerationPreparationWorker moderationPreparationWorker(ModerationStore store,PreparationFilePolicy policy,ReviewFileAccess files,
                                                            OpenAiReviewClient client,PlatformTransactionManager transactions,Clock clock) {
        return new ModerationPreparationWorker(store,policy,files,client,transactions,clock);
    }

    static String readCredential(Path path) {
        path=path.toAbsolutePath().normalize();
        try {
            var owner=Files.getOwner(Path.of("."));
            Path current=path.getRoot();
            for(Path component:path) {
                current=current.resolve(component);
                if(Files.isSymbolicLink(current)) { throw new IOException(); }
            }
            if(!Files.isDirectory(path.getParent(),LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(path.getParent(),LinkOption.NOFOLLOW_LINKS).equals(owner)
                    || !Files.getPosixFilePermissions(path.getParent(),LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) { throw new IOException(); }
            var attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile() || !attributes.owner().equals(owner)
                    || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------")) || attributes.size()<1 || attributes.size()>128
                    || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1) { throw new IOException(); }
            byte[] bytes;
            try(var channel=FileChannel.open(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                if(channel.size()!=attributes.size()) { throw new IOException(); }
                ByteBuffer buffer=ByteBuffer.allocate((int)attributes.size());
                while(buffer.hasRemaining()) { if(channel.read(buffer)<0) { throw new IOException(); } }
                if(channel.read(ByteBuffer.allocate(1))!=-1) { throw new IOException(); }
                bytes=buffer.array();
            }
            for(byte value:bytes) { if(value<32 || value>126) { throw new IOException(); } }
            String credential=new String(bytes,StandardCharsets.US_ASCII);
            if(!credential.matches("LOCAL_PROTOCOL_FIXTURE_[A-Za-z0-9_-]{32,96}")) { throw new IOException(); }
            return credential;
        } catch(IOException | RuntimeException failure) { throw new IllegalArgumentException("Preparation credential unavailable"); }
    }

    record PreparationSettings(URI origin,Path credential) {}
}
