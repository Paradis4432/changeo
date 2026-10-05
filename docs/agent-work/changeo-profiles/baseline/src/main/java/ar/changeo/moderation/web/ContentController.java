package ar.changeo.moderation.web;

import ar.changeo.files.api.*;
import ar.changeo.moderation.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ContentController {
    private final ContentReader reader;
    private final WorkbenchService workbench;
    private final FileAccess files;
    public ContentController(ContentReader reader,WorkbenchService workbench,FileAccess files) { this.reader=reader;this.workbench=workbench;this.files=files; }
    @GetMapping("/content") public String search(@RequestParam(defaultValue="") String q,Model model) { model.addAttribute("q",q);model.addAttribute("results",workbench.search(q));return "content-search"; }
    @GetMapping("/content/{id}") public String content(@PathVariable UUID id,Model model) { model.addAttribute("content",reader.retrieve(id));model.addAttribute("command",UUID.randomUUID());return "content-detail"; }
    @GetMapping("/content/{id}/preview") @ResponseBody public ContentReader.ContentView preview(@PathVariable UUID id) { return reader.retrieve(id); }
    @GetMapping("/content/{id}/notification") @ResponseBody public ContentReader.ContentView notification(@PathVariable UUID id) { return reader.retrieve(id); }
    @GetMapping("/content/files/{id}") public ResponseEntity<byte[]> file(@PathVariable UUID id,@RequestParam UUID resource,@RequestParam UUID revision,@RequestParam String digest) {
        return ModerationController.download(files.read(new FileReference(id,workbench.reference(resource,revision),digest),FileAccess.Purpose.ORDINARY));
    }
}
