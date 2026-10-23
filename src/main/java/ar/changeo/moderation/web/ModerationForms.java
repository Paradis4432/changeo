package ar.changeo.moderation.web;

import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.util.MultiValueMap;
import org.springframework.util.NumberUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.Errors;

public final class ModerationForms {
    private static final Set<String> REASONS = Set.of("MISLABEL", "SAFETY", "FALSE_POSITIVE", "OTHER");
    private ModerationForms() {}

    public record Draft(String command,String resource,String version,String text,String label,String audience,String subject,String participant) {
        public Draft {
            command=value(command);resource=value(resource);version=value(version);text=value(text);label=value(label);audience=value(audience);subject=value(subject);participant=value(participant);
        }
        static Draft initial(UUID command) { return new Draft(command.toString(),"","0","","GENERAL","PUBLIC","",""); }
        Draft withTypedParameters(MultiValueMap<String,String> parameters) {
            var versions=parameters.get("version");
            String version=versions==null || versions.size()==1 && versions.getFirst().isEmpty()?"0":versions.getFirst();
            return new Draft(parameters.getFirst("command"),parameters.getFirst("resource"),version,text,label,audience,parameters.getFirst("subject"),parameters.getFirst("participant"));
        }
        Context context() {
            if(!Set.of("PUBLIC","PRIVATE").contains(audience)) { throw new IllegalArgumentException(); }
            var id=uuid(resource);
            return new Context(uuid(command).orElseThrow(IllegalArgumentException::new),id,NumberUtils.parseNumber(version,Long.class),audience,id.isEmpty()?uuid(subject):Optional.empty());
        }
        void validate(Errors errors) {
            boolean valid=true;
            try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)); }
            catch(CharacterCodingException failure) { valid=false; }
            if(text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length>32768 || !valid) {
                errors.rejectValue("text","invalid","Escribí texto UTF-8 de hasta 32 KiB; revisá el texto conservado, que puede estar recortado");
            }
            if(!Set.of("GENERAL","SENSITIVE","ADULT").contains(label)) { errors.rejectValue("label","invalid","Elegí una etiqueta de la lista"); }
            validateUuid(errors,"subject",subject);
            validateUuid(errors,"participant",participant);
            if(audience.equals("PUBLIC") && StringUtils.hasText(participant)) { errors.rejectValue("participant","invalid","Dejá el participante vacío para una audiencia pública"); }
        }
        Draft bounded() {
            var parsed=context();
            return new Draft(parsed.command().toString(),parsed.resource().map(UUID::toString).orElse(""),Long.toString(parsed.version()),boundedText(text,32768),Set.of("GENERAL","SENSITIVE","ADULT").contains(label)?label:"",audience,boundedText(subject,36),boundedText(participant,36));
        }
        Optional<UUID> subjectId() { return uuid(subject); }
        Optional<UUID> participantId() { return uuid(participant); }
    }
    record Context(UUID command,Optional<UUID> resource,long version,String audience,Optional<UUID> subject) {}
    private static Optional<UUID> uuid(String value) { return StringUtils.hasText(value)?Optional.of(UUID.fromString(value.trim())):Optional.empty(); }
    private static void validateUuid(Errors errors,String field,String value) {
        try { uuid(value); }
        catch(IllegalArgumentException failure) { errors.rejectValue(field,"invalid","Ingresá un UUID válido o dejá el campo vacío"); }
    }
    private static String value(String value) { return Objects.requireNonNullElse(value,""); }
    private static String boundedText(String value,int maximumBytes) {
        StringBuilder result=new StringBuilder();int bytes=0;
        for(int offset=0;offset<value.length();) {
            int point=value.codePointAt(offset);offset+=Character.charCount(point);
            if(point>=0xD800 && point<=0xDFFF || Character.isISOControl(point) && point!='\n' && point!='\r' && point!='\t') { continue; }
            int size=point<=0x7F?1:point<=0x7FF?2:point<=0xFFFF?3:4;
            if(bytes+size>maximumBytes) { break; }
            result.appendCodePoint(point);bytes+=size;
        }
        return result.toString();
    }

    public record Case(String reason, String note) {
        void validate(Errors errors) {
            if (!REASONS.contains(reason)) {
                errors.rejectValue("reason", "invalid", "Elegí un motivo de la lista");
            }
            if (note.length() > 2000 || note.indexOf('\0') >= 0) {
                errors.rejectValue("note", "invalid", "Usá hasta 2000 caracteres sin caracteres nulos; revisá el texto conservado");
            }
        }

        Case bounded() {
            return new Case(REASONS.contains(reason) ? reason : "",
                    note.substring(0, Math.min(note.length(), 2000)).replace("\0", ""));
        }
    }
}
