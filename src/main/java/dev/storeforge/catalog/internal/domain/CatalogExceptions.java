package dev.storeforge.catalog.internal.domain;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public final class CatalogExceptions {

    private CatalogExceptions() {
    }

    public static class ProductNotFound extends StoreForgeException {
        public ProductNotFound() {
            super(HttpStatus.NOT_FOUND, "product_not_found", "Product not found.");
        }
    }

    public static class VariantNotFound extends StoreForgeException {
        public VariantNotFound() {
            super(HttpStatus.NOT_FOUND, "variant_not_found", "Variant not found.");
        }
    }

    /** A business rule of the product aggregate was violated (e.g. option values don't match option names). */
    public static class InvalidProduct extends StoreForgeException {
        public InvalidProduct(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_product", detail);
        }
    }

    public static class HandleTaken extends StoreForgeException {
        public HandleTaken() {
            super(HttpStatus.CONFLICT, "handle_taken", "Another product in this store already uses this handle.");
        }
    }

    public static class SkuTaken extends StoreForgeException {
        public SkuTaken() {
            super(HttpStatus.CONFLICT, "sku_taken", "Another variant in this store already uses this SKU.");
        }
    }

    public static class DuplicateVariant extends StoreForgeException {
        public DuplicateVariant() {
            super(HttpStatus.CONFLICT, "duplicate_variant", "A variant with these option values already exists.");
        }
    }

    public static class LastVariant extends StoreForgeException {
        public LastVariant() {
            super(HttpStatus.CONFLICT, "last_variant", "A product must keep at least one variant.");
        }
    }

    public static class PreconditionFailed extends StoreForgeException {
        public PreconditionFailed() {
            super(HttpStatus.PRECONDITION_FAILED, "precondition_failed",
                    "The product was modified since you loaded it (If-Match does not match). Reload and retry.");
        }
    }

    public static class ImageNotFound extends StoreForgeException {
        public ImageNotFound() {
            super(HttpStatus.NOT_FOUND, "image_not_found", "Image not found.");
        }
    }

    /** The requested upload is not allowed (type, size). */
    public static class InvalidImage extends StoreForgeException {
        public InvalidImage(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_image", detail);
        }
    }

    public static class TooManyImages extends StoreForgeException {
        public TooManyImages(int max) {
            super(HttpStatus.CONFLICT, "too_many_images", "A product can have at most " + max + " images (including uploads in progress).");
        }
    }

    /** Completion was requested but nothing has been uploaded to the URL (yet). */
    public static class UploadMissing extends StoreForgeException {
        public UploadMissing() {
            super(HttpStatus.CONFLICT, "upload_missing", "Nothing has been uploaded for this image yet. PUT the file to the upload URL first.");
        }
    }

    /** The stored object isn't what was declared: wrong size or not that image format. The upload is discarded. */
    public static class UploadRejected extends StoreForgeException {
        public UploadRejected(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "upload_rejected", detail);
        }
    }
}
