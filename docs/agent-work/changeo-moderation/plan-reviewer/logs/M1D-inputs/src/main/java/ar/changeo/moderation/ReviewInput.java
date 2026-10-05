package ar.changeo.moderation;

import ar.changeo.moderation.api.ContentReference;
import ar.changeo.files.api.FileReference;
import java.util.List;

public record ReviewInput(ContentReference reference, String text, String declaredLabel, String digest, List<FileReference> attachments) {
    public ReviewInput { attachments = List.copyOf(attachments); }
}
