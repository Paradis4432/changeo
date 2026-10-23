package ar.changeo.moderation;

import ar.changeo.files.FileInspector;
import ar.changeo.files.api.*;
import ar.changeo.moderation.api.*;
import ar.changeo.moderation.ReviewObservation.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiReviewClientTest {
    static final String CREDENTIAL="LOCAL_PROTOCOL_FIXTURE_"+"f".repeat(32);
    static final JsonMapper JSON=JsonMapper.builder().build();
    private final List<OpenAiReviewClient> clients=new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger wires=new AtomicInteger();

    @AfterEach void close() {
        clients.forEach(OpenAiReviewClient::close);
        if(server!=null) { server.stop(0); }
        if(executor!=null) { executor.shutdownNow(); try { assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
    }
    private OpenAiReviewClient client() { return client(URI.create("http://127.0.0.1:1"),Duration.ofSeconds(15)); }
    private OpenAiReviewClient client(URI origin,Duration deadline) {
        var client=new OpenAiReviewClient(origin,CREDENTIAL,new OpenAiReviewClient.Limits(Duration.ofSeconds(2),deadline));clients.add(client);return client;
    }
    static ReviewInput input(String text,List<FileReference> files) {
        ContentReference ref=files.isEmpty()?new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID()):files.getFirst().content();
        return new ReviewInput(ref,text,"GENERAL",ContentDigest.payload(text,"GENERAL",files),files);
    }
    private OpenAiReviewClient.Encoded empty(OpenAiReviewClient client,Wire wire) { return client.encode(wire,input("harmless fixture",List.of()),new ReviewFileAccess.Material(List.of())); }
    static byte[] moderation() {
        var flags=new TreeMap<String,Boolean>();var scores=new TreeMap<String,Double>();var coverage=new TreeMap<String,List<String>>();
        OpenAiReviewClient.CATEGORIES.forEach(name->{flags.put(name,false);scores.put(name,0.0);coverage.put(name,List.of("text"));});
        return JSON.writeValueAsBytes(Map.of("id","modr_fixture","model",Wire.MODERATIONS.model(),"results",List.of(Map.of("flagged",false,"categories",flags,"category_scores",scores,"category_applied_input_types",coverage))));
    }
    static byte[] response() {
        return JSON.writeValueAsBytes(Map.of("id","resp_fixture","model",Wire.RESPONSES.model(),"status","completed","error",JSON.nullNode(),"incomplete_details",JSON.nullNode(),
                "output",List.of(Map.of("type","message","role","assistant","status","completed","content",List.of(Map.of("type","output_text","text","{\"proposed_action\":\"APPROVE\",\"proposed_label\":\"GENERAL\",\"reason\":\"CLEAR\",\"uncertain\":false}"))))));
    }

    @Test void preservesWholeMaximumEscapedMaterialInBothRequests() {
        var ref=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID());
        byte[] content="\\\"\n\r\t".repeat(209716).substring(0,1048576).getBytes(StandardCharsets.UTF_8);
        assertThat(new FileInspector().inspect("text/plain",content).safe()).isTrue();
        List<ReviewFileAccess.File> material=new ArrayList<>();
        for(int n=0;n<4;n++) { material.add(new ReviewFileAccess.File(new FileReference(UUID.randomUUID(),ref,ContentDigest.bytes(content)),"text/plain",content)); }
        var original=input("\u0001".repeat(32768),material.stream().map(ReviewFileAccess.File::reference).toList());
        var client=client();
        for(var wire:Wire.values()) {
            var encoded=client.encode(wire,original,new ReviewFileAccess.Material(material));
            assertThat(encoded.size()).isLessThanOrEqualTo(OpenAiReviewClient.PROVED_BODY_BYTES);
            var body=JSON.readTree(encoded.bytes());
            String text=wire==Wire.MODERATIONS?body.path("input").stringValue():body.path("input").get(0).path("content").get(0).path("text").stringValue();
            StringBuilder expected=new StringBuilder(original.text());
            for(int n=1;n<=4;n++) { expected.append("\n[attachment-").append(n).append(":text/plain]\n").append(new String(content,StandardCharsets.UTF_8)); }
            assertThat(text.equals(expected.toString())).isTrue();
            assertThat(encoded.hash()).isEqualTo(ContentDigest.bytes(encoded.bytes()));
            assertThat(JSON.readTree(encoded.manifest()).path("files").size()).isEqualTo(4);
        }
    }

    @Test void preservesNativeUnicodeAndSupplementaryPairsAcrossWriterSegments() {
        String text=("\u00a0\u2028\u2029漢🙂/".repeat(3000)).substring(0,12000);
        if(Character.isHighSurrogate(text.charAt(text.length()-1))) { text=text.substring(0,text.length()-1); }
        var client=client();var original=input(text,List.of());
        for(var wire:Wire.values()) {
            var body=JSON.readTree(client.encode(wire,original,new ReviewFileAccess.Material(List.of())).bytes());
            String actual=wire==Wire.MODERATIONS?body.path("input").stringValue():body.path("input").get(0).path("content").get(0).path("text").stringValue();
            assertThat(actual.equals(text)).isTrue();
        }
    }

    @Test void maximumNonAsciiFilesRemainSupportedAndGlobalAsciiEscapingWouldBreachCap() {
        String text="\u00a0".repeat(524288);byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(1048576);assertThat(new FileInspector().inspect("text/plain",bytes).safe()).isTrue();
        var ref=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID());List<ReviewFileAccess.File> material=new ArrayList<>();
        for(int n=0;n<4;n++) { material.add(new ReviewFileAccess.File(new FileReference(UUID.randomUUID(),ref,ContentDigest.bytes(bytes)),"text/plain",bytes)); }
        var original=input("\u2028\u2029漢🙂".repeat(2000),material.stream().map(ReviewFileAccess.File::reference).toList());
        var client=client();
        for(var wire:Wire.values()) {
            byte[] encoded=client.encode(wire,original,new ReviewFileAccess.Material(material)).bytes();assertThat(encoded.length).isLessThan(OpenAiReviewClient.MAX_REQUEST_BYTES);
            var root=JSON.readTree(encoded);String decoded=wire==Wire.MODERATIONS?root.path("input").stringValue():root.path("input").get(0).path("content").get(0).path("text").stringValue();
            assertThat(decoded.contains(text)).isTrue();
            var ascii=JsonMapper.builder().enable(tools.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII).build();
            assertThat(ascii.writeValueAsBytes(decoded).length).isGreaterThan(OpenAiReviewClient.MAX_REQUEST_BYTES);
        }
    }

    @Test void validProtocolApprovalRemainsTypedUntrustedObservation() {
        var client=client();
        assertThat(client.parse(Wire.MODERATIONS,moderation(),false,200).outcome()).isEqualTo(Outcome.VALID);
        var observed=client.parse(Wire.RESPONSES,response(),false,200);
        assertThat(observed.outcome()).isEqualTo(Outcome.VALID);
        assertThat(observed.classification().action()).isEqualTo("APPROVE");
        assertThat(observed.normalized()).hasSize(4);
    }

    @ParameterizedTest @ValueSource(strings={"empty","prose","unknownField","missingId"})
    void acceptsOnlyKnownEmptyReasoningWithoutPersistingGeneratedProse(String kind) {
        var root=JSON.readTree(response()).asObject();
        var reasoning=JSON.valueToTree(Map.of("id","reason_fixture","type","reasoning","summary",List.of(),"status","completed")).asObject();
        switch(kind) {
            case "prose"->reasoning.set("summary",JSON.valueToTree(List.of(Map.of("type","summary_text","text","untrusted private reasoning"))));
            case "unknownField"->reasoning.put("future_prose","untrusted private reasoning");
            case "missingId"->reasoning.remove("id");
            default->{ }
        }
        root.withArray("output").insert(0,reasoning);
        var observed=client().parse(Wire.RESPONSES,JSON.writeValueAsBytes(root),false,200);
        assertThat(observed.outcome()).isEqualTo(kind.equals("empty")?Outcome.VALID:Outcome.INVALID);
        assertThat(OpenAiReviewClient.json(observed.normalized())).doesNotContain("reason_fixture","untrusted private reasoning");
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","unknownCategory","wrongType","model","many","range","encoding","depth"})
    void rejectsMalformedModeration(String kind) {
        byte[] bytes=moderation();String value=new String(bytes,StandardCharsets.UTF_8);
        value=switch(kind) {
            case "duplicate"->value.replace("\"flagged\":false","\"flagged\":false,\"flagged\":false");
            case "trailing"->value+" {}";
            case "unknownCategory"->value.replace("\"hate\":false","\"other\":false");
            case "wrongType"->value.replace("\"flagged\":false","\"flagged\":\"false\"");
            case "model"->value.replace(Wire.MODERATIONS.model(),"other-model");
            case "many"->value.replace("\"results\":[","\"results\":[{},");
            case "range"->value.replace("0.0","2.0");
            case "depth"->"{\"extra\":"+"[".repeat(20)+"0"+"]".repeat(20)+",\"ok\":true}";
            default->value;
        };
        bytes=value.getBytes(StandardCharsets.UTF_8);
        if(kind.equals("encoding")) { bytes=new byte[]{(byte)0xc3,(byte)0x28}; }
        assertThat(client().parse(Wire.MODERATIONS,bytes,false,200).outcome()).isEqualTo(Outcome.INVALID);
    }

    @ParameterizedTest @ValueSource(strings={"refusal","incomplete","unknown","tool","multiple","contradiction","model","duplicate","trailing"})
    void handlesStructuredFailureWithoutInventingApproval(String kind) {
        var root=JSON.readTree(response()).asObject();
        switch(kind) {
            case "refusal"->root.withArray("output").get(0).asObject().set("content",JSON.valueToTree(List.of(Map.of("type","refusal","refusal","private fixture"))));
            case "incomplete"->root.put("status","incomplete");
            case "unknown"->root.put("status","future");
            case "tool"->root.withArray("output").get(0).asObject().put("type","function_call");
            case "multiple"->root.withArray("output").add(root.path("output").get(0).deepCopy());
            case "model"->root.put("model","another-model");
            case "contradiction"->root.withArray("output").get(0).path("content").get(0).asObject().put("text","{\"proposed_action\":\"APPROVE\",\"proposed_label\":\"GENERAL\",\"reason\":\"PROHIBITED\",\"uncertain\":false}");
            default->{ }
        }
        String body=JSON.writeValueAsString(root);
        if(kind.equals("duplicate")) { body=body.replace("\"status\":\"completed\"","\"status\":\"completed\",\"status\":\"completed\""); }
        if(kind.equals("trailing")) { body+=" {}"; }
        var result=client().parse(Wire.RESPONSES,body.getBytes(StandardCharsets.UTF_8),false,200);
        assertThat(result.outcome()).isEqualTo(kind.equals("refusal")?Outcome.REFUSAL:kind.equals("incomplete")?Outcome.INCOMPLETE:Outcome.INVALID);
        assertThat(result.classification()).isNull();
    }

    @ParameterizedTest @ValueSource(ints={200,302,401,403,429,500})
    void sendsExactlyOneRequestAndRefusesRedirect(int status) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<byte[]> received=new AtomicReference<>();
        server.createContext("/v1/moderations",exchange->{
            wires.incrementAndGet();received.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Location","/v1/moderations");
            byte[] body=moderation();exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2));
        var request=empty(client,Wire.MODERATIONS);var result=client.send(request);
        assertThat(wires.get()).isEqualTo(1);assertThat(Arrays.equals(received.get(),request.bytes())).isTrue();
        assertThat(result.outcome()).isEqualTo(status==200?Outcome.VALID:status==429 || status==500?Outcome.UNAVAILABLE:Outcome.INVALID);
    }

    @ParameterizedTest @ValueSource(strings={"stalled","dribbling","oversized"})
    void boundsEntireBodyAndRecovers(String mode) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/v1/moderations",exchange->{
            int attempt=wires.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");
            try {
                if(attempt>1) { byte[] body=moderation();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return; }
                exchange.sendResponseHeaders(200,0);
                if(mode.equals("oversized")) { exchange.getResponseBody().write(new byte[65537]); }
                else { for(int n=0;n<200;n++) { if(mode.equals("dribbling")) { exchange.getResponseBody().write(' ');exchange.getResponseBody().flush(); } Thread.sleep(20); } }
            } catch(InterruptedException failure) { Thread.currentThread().interrupt(); } catch(java.io.IOException closed) { }
            finally { exchange.close(); }
        });server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(250));
        long start=System.nanoTime();var result=client.send(empty(client,Wire.MODERATIONS));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(client.send(empty(client,Wire.MODERATIONS)).outcome()).isEqualTo(Outcome.VALID);
        assertThat(wires.get()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"reset","short","gzip","nonJson","malformed"})
    void preservesOneWireForProtocolAndConnectionFailures(String mode) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/moderations",exchange->{
            wires.incrementAndGet();exchange.getRequestBody().readNBytes(OpenAiReviewClient.MAX_REQUEST_BYTES+1);
            try {
                if(mode.equals("reset")) { return; }
                exchange.getResponseHeaders().set("Content-Type",mode.equals("nonJson")?"text/plain":"application/json");
                if(mode.equals("gzip")) { exchange.getResponseHeaders().set("Content-Encoding","gzip"); }
                byte[] body=mode.equals("malformed")?new byte[]{'{'}:moderation();
                exchange.sendResponseHeaders(200,mode.equals("short")?body.length+5:body.length);exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
            } finally { exchange.close(); }
        });server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2));
        var result=client.send(empty(client,Wire.MODERATIONS));
        assertThat(result.outcome()).isEqualTo(Set.of("reset","short").contains(mode)?Outcome.UNKNOWN:Outcome.INVALID);
        assertThat(wires.get()).isEqualTo(1);
        if(mode.equals("short")) { assertThat(result.httpStatus()).isEqualTo(200); }
    }

    @Test void interruptionCancelsWholeResponseAndClientCloseTerminatesPendingWork() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/v1/moderations",exchange->{
            wires.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);entered.countDown();
            try { release.await(3,TimeUnit.SECONDS); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2));
        var result=new AtomicReference<ReviewObservation>();var preserved=new AtomicBoolean();
        Thread thread=Thread.ofVirtual().start(()->{result.set(client.send(empty(client,Wire.MODERATIONS)));preserved.set(Thread.currentThread().isInterrupted());});
        try { assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();thread.interrupt();thread.join(2000);assertThat(thread.isAlive()).isFalse(); }
        finally { release.countDown(); }
        assertThat(result.get().reason()).isEqualTo(Reason.INTERRUPTED);assertThat(preserved.get()).isTrue();assertThat(wires.get()).isEqualTo(1);
        client.close();assertThat(client.send(empty(client,Wire.MODERATIONS)).reason()).isEqualTo(Reason.CLOSED);
    }

    private static List<List<String>> combinations(List<String> prefix) {
        var values=new ArrayList<List<String>>();values.add(prefix);
        if(prefix.size()<4) { for(String type:List.of("text/plain","image/png","image/jpeg")) { var next=new ArrayList<>(prefix);next.add(type);values.addAll(combinations(List.copyOf(next))); } }
        return values;
    }
    private static byte[] image(String type) throws Exception {
        var image=new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output=new java.io.ByteArrayOutputStream();assertThat(javax.imageio.ImageIO.write(image,type.equals("image/png")?"png":"jpeg",output)).isTrue();
        return Arrays.copyOf(output.toByteArray(),1048576);
    }
    @Test void allSupportedCombinationsHaveSmallActualEncoderEnvelopeAndCompleteFakeWire() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new AtomicReference<byte[]>();
        server.createContext("/v1/",exchange->{wires.incrementAndGet();received.set(exchange.getRequestBody().readNBytes(OpenAiReviewClient.MAX_REQUEST_BYTES+1));exchange.getResponseHeaders().set("Content-Type","application/json");byte[] body=exchange.getRequestURI().getPath().equals(Wire.MODERATIONS.path())?moderation():response();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2));
        var ref=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID());
        long maximumEnvelope=0;
        for(var types:combinations(List.of())) {
            List<ReviewFileAccess.File> material=new ArrayList<>();
            for(String type:types) {
                byte[] bytes=type.equals("text/plain")?"whole\\\"\n\u00a0🙂".getBytes(StandardCharsets.UTF_8):image(type);
                assertThat(new FileInspector().inspect(type,bytes).safe()).isTrue();
                material.add(new ReviewFileAccess.File(new FileReference(UUID.randomUUID(),ref,ContentDigest.bytes(bytes)),type,bytes));
            }
            var original=input("full\u00a0main\u2028🙂",material.stream().map(ReviewFileAccess.File::reference).toList());
            for(var wire:Wire.values()) {
                var encoded=client.encode(wire,original,new ReviewFileAccess.Material(material));
                assertThat(client.send(encoded).outcome()).isEqualTo(Outcome.VALID);
                assertThat(Arrays.equals(received.get(),encoded.bytes())).isTrue();
                var body=JSON.readTree(received.get());var parts=wire==Wire.MODERATIONS?body.path("input"):body.path("input").get(0).path("content");
                String text=parts.isString()?parts.stringValue():parts.get(0).path("text").stringValue();
                long payload=JSON.writeValueAsBytes(text).length-2;
                int imageIndex=1;
                for(var file:material) {
                    if(file.mediaType().equals("text/plain")) { assertThat(text.contains(new String(file.bytes(),StandardCharsets.UTF_8))).isTrue();continue; }
                    String url=wire==Wire.MODERATIONS?parts.get(imageIndex).path("image_url").path("url").stringValue():parts.get(imageIndex).path("image_url").stringValue();
                    String base64=url.substring(url.indexOf(',')+1);assertThat(Arrays.equals(Base64.getDecoder().decode(base64),file.bytes())).isTrue();payload+=base64.length();imageIndex++;
                }
                maximumEnvelope=Math.max(maximumEnvelope,encoded.size()-payload);
                assertThat(encoded.size()-payload).isLessThanOrEqualTo(65536);
            }
        }
        assertThat(wires.get()).isEqualTo(242);assertThat(maximumEnvelope).isLessThanOrEqualTo(65536);
    }

    @Test void twoMaximumPipelinesSendSequentialStagesWithMeasuredHeapAndAllocation() throws Exception {
        Path output=Path.of(".runtime/external-moderation-preparation-20261020/resource-"+UUID.randomUUID());
        Files.createDirectories(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String main="\u0001".repeat(32768);byte[] bytes="\\".repeat(1048576).getBytes(StandardCharsets.UTF_8);
        assertThat(new FileInspector().inspect("text/plain",bytes).safe()).isTrue();
        var ref=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID());
        List<ReviewFileAccess.File> files=new ArrayList<>();for(int n=0;n<4;n++) { files.add(new ReviewFileAccess.File(new FileReference(UUID.randomUUID(),ref,ContentDigest.bytes(bytes)),"text/plain",bytes)); }
        var original=input(main,files.stream().map(ReviewFileAccess.File::reference).toList());var material=new ReviewFileAccess.Material(files);
        var firstStages=new CountDownLatch(2);var release=new CountDownLatch(1);var matched=new AtomicInteger();var largest=new AtomicLong();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/v1/",exchange->{
            int count=wires.incrementAndGet();byte[] body=exchange.getRequestBody().readNBytes(OpenAiReviewClient.MAX_REQUEST_BYTES+1);largest.accumulateAndGet(body.length,Math::max);
            Path capture=output.resolve("body-"+count+".json");Files.write(capture,body,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(capture,PosixFilePermissions.fromString("rw-------"));
            var tree=JSON.readTree(body);String text=exchange.getRequestURI().getPath().equals(Wire.MODERATIONS.path())?tree.path("input").stringValue():tree.path("input").get(0).path("content").get(0).path("text").stringValue();
            boolean complete=text.startsWith(main) && text.length()==32768+4*1048576+4*27;
            if(complete) { matched.incrementAndGet(); }
            try { if(exchange.getRequestURI().getPath().equals(Wire.MODERATIONS.path())) { firstStages.countDown();if(!release.await(5,TimeUnit.SECONDS)) { throw new java.io.IOException(); } }
                byte[] response=exchange.getRequestURI().getPath().equals(Wire.MODERATIONS.path())?moderation():response();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            } catch(InterruptedException failure) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });server.start();
        var client=client(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(15));
        var memory=ManagementFactory.getMemoryMXBean();var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long heapBefore=memory.getHeapMemoryUsage().getUsed();long allocatedBefore=allocation.getTotalThreadAllocatedBytes();var peak=new AtomicLong(heapBefore);var sampling=new AtomicBoolean(true);
        Thread sampler=Thread.ofPlatform().start(()->{while(sampling.get()) { peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max);try { Thread.sleep(5); } catch(InterruptedException failure) { Thread.currentThread().interrupt();return; } }});
        try(var pipelines=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int n=0;n<2;n++) { results.add(pipelines.submit(()->{for(var wire:Wire.values()) { if(!maximumStage(client,wire,original,material)) { return false; } }return true;})); }
            try { assertThat(firstStages.await(5,TimeUnit.SECONDS)).isTrue();assertThat(wires.get()).isEqualTo(2); }
            finally { release.countDown(); }
            for(var result:results) { assertThat(result.get(10,TimeUnit.SECONDS)).isTrue(); }
        } finally { sampling.set(false);sampler.join(2000); }
        assertThat(wires.get()).isEqualTo(4);assertThat(matched.get()).isEqualTo(4);assertThat(largest.get()).isLessThanOrEqualTo(OpenAiReviewClient.PROVED_BODY_BYTES);
        Map<String,Object> observations=Map.of("pipelines",2,"wireRequests",wires.get(),"maximumRequestBytes",largest.get(),"heapBeforeBytes",heapBefore,"sampledHeapMaximumBytes",peak.get(),"allocatedBytes",allocation.getTotalThreadAllocatedBytes()-allocatedBefore,"samplePeriodMillis",5,"heapLimitBytes",memory.getHeapMemoryUsage().getMax());
        Path measurement=output.resolve("measurement.json");Files.writeString(measurement,JSON.writeValueAsString(observations),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(measurement,PosixFilePermissions.fromString("rw-------"));
    }
    private static boolean maximumStage(OpenAiReviewClient client,Wire wire,ReviewInput input,ReviewFileAccess.Material material) { return client.send(client.encode(wire,input,material)).outcome()==Outcome.VALID; }
}
