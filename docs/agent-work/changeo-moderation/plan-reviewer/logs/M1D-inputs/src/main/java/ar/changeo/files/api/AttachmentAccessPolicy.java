package ar.changeo.files.api;

public interface AttachmentAccessPolicy {
    void requireCurrentAccess(FileReference reference, FileAccess.Purpose purpose);
}
