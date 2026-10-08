package dev.storeforge.catalog.internal.web;

import dev.storeforge.catalog.internal.application.ProductImageService;
import dev.storeforge.catalog.internal.application.ProductImageService.ImageView;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product images: presigned upload, verified completion, list, delete ({@code products:write} / {@code products:read}). */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/products/{productId}/images")
@RequiredArgsConstructor
class AdminProductImagesController {

    private final ProductImageService images;

    record UploadRequest(@NotBlank String contentType, @NotNull Long sizeBytes, @Nullable @Size(max = 500) String alt) {
    }

    record UploadInstructions(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record UploadResponse(UUID imageId, UploadInstructions upload) {
    }

    @PostMapping("/uploads")
    ResponseEntity<UploadResponse> startUpload(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                               @PathVariable UUID productId, @Valid @RequestBody UploadRequest body) {
        var started = images.startUpload(user.userId(), StoreId.of(storeId), productId, body.contentType(), body.sizeBytes(), body.alt());
        var upload = started.upload();
        // The client must send exactly these headers: they are part of the signature.
        var headers = Map.of("Content-Type", upload.contentType(), "Content-Length", Long.toString(upload.contentLength()));
        return ResponseEntity.status(201).body(new UploadResponse(started.imageId(),
                new UploadInstructions(upload.url(), upload.method(), headers, upload.expiresAt())));
    }

    @PostMapping("/{imageId}/completion")
    ImageView complete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID productId,
                       @PathVariable UUID imageId) {
        return images.complete(user.userId(), StoreId.of(storeId), productId, imageId);
    }

    @GetMapping
    Map<String, List<ImageView>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID productId) {
        return Map.of("data", images.list(user.userId(), StoreId.of(storeId), productId));
    }

    @DeleteMapping("/{imageId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID productId,
                                @PathVariable UUID imageId) {
        images.delete(user.userId(), StoreId.of(storeId), productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
