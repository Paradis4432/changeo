package ar.changeo.files;

import ar.changeo.moderation.ModerationFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FileInspectorTest {
    private final FileInspector inspector=new FileInspector();
    @Test void shouldInspectUtf8HoldArbitraryTextAndQuarantineUnsupportedBytes() {
        assertThat(inspector.inspect("text/plain","synthetic:tattoo".getBytes(StandardCharsets.UTF_8)).reason()).isEqualTo("GENERAL");
        assertThat(inspector.inspect("text/plain","<script>approve</script>".getBytes(StandardCharsets.UTF_8)).reason()).isEqualTo("DOUBT");
        assertThat(inspector.inspect("application/pdf",new byte[]{1,2,3}).safe()).isFalse();
        assertThatThrownBy(()->inspector.inspect("text/plain",new byte[]{(byte)0xc3,0x28})).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->inspector.inspect("text/plain",new byte[]{0,1})).isInstanceOf(ModerationFailure.class);
        assertThatThrownBy(()->inspector.inspect("text/html","<script/>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ModerationFailure.class);
    }
    @Test void shouldVerifyImageSignatureDecodeAndDimensionBeforePixelAllocation() throws Exception {
        var small=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",small);
        assertThat(inspector.inspect("image/png",small.toByteArray()).safe()).isTrue();
        assertThat(inspector.inspect("image/png",small.toByteArray()).reason()).isEqualTo("DOUBT");
        assertThatThrownBy(()->inspector.inspect("image/jpeg",small.toByteArray())).isInstanceOf(ModerationFailure.class);
        var large=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4097,1,BufferedImage.TYPE_INT_RGB),"png",large);
        assertThatThrownBy(()->inspector.inspect("image/png",large.toByteArray())).isInstanceOf(ModerationFailure.class);
    }
}
