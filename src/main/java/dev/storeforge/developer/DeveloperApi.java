package dev.storeforge.developer;

import java.util.List;

/** Paths and versions of the developer API. Versions are dates; each is supported for at least 12 months. */
public final class DeveloperApi {

    public static final String CURRENT_VERSION = "2026-10";
    public static final List<String> SUPPORTED_VERSIONS = List.of(CURRENT_VERSION);
    /** Base path of every store resource; controllers map below it. */
    public static final String STORE_PATH = "/api/{apiVersion}/stores/{storeId}";
    public static final String VERSION_HEADER = "X-StoreForge-Api-Version";

    private DeveloperApi() {
    }
}
