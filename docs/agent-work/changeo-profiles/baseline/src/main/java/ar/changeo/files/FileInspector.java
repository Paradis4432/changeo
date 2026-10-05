package ar.changeo.files;

import ar.changeo.moderation.ModerationFailure;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class FileInspector {
    public Inspection inspect(String type,byte[] bytes) {
        if ("text/plain".equals(type)) {
            try {
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if(text.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) { throw new ModerationFailure("Formato o bytes inválidos"); }
                return new Inspection(true,switch(text) { case "synthetic:clear", "synthetic:tattoo" -> "GENERAL"; case "synthetic:medical" -> "SENSITIVE"; case "synthetic:adult" -> "ADULT"; case "synthetic:prohibited" -> "PROHIBITED"; default -> "DOUBT"; });
            } catch(CharacterCodingException failure) { throw new ModerationFailure("Texto UTF-8 inválido"); }
        }
        if (Set.of("image/png","image/jpeg").contains(type)) {
            boolean signature=type.equals("image/png") ? bytes.length>8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}) : bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
            if(!signature) { throw new ModerationFailure("Formato o bytes inválidos"); }
            try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext()) { throw new ModerationFailure("Formato o bytes inválidos"); }
                var reader=readers.next();
                try {
                    reader.setInput(stream,true,true);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || width>4096 || height>4096 || (long)width*height>4000000) { throw new ModerationFailure("Imagen supera dimensiones locales"); }
                    BufferedImage image=reader.read(0);
                    if(image==null) { throw new ModerationFailure("Formato o bytes inválidos"); }
                    return new Inspection(true,"DOUBT");
                } finally { reader.dispose(); }
            } catch(IOException failure) { throw new ModerationFailure("Imagen no inspeccionable"); }
        }
        if(Set.of("application/pdf","application/octet-stream","model/stl").contains(type)) { return new Inspection(false,"UNINSPECTABLE"); }
        throw new ModerationFailure("Formato activo o no soportado");
    }
    public record Inspection(boolean safe,String reason) {}
}
