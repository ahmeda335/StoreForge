package dev.storeforge.stores.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.internal.application.StoreService;
import dev.storeforge.stores.internal.web.StoreDtos.CreateStoreRequest;
import dev.storeforge.stores.internal.web.StoreDtos.RenameStoreRequest;
import dev.storeforge.stores.internal.web.StoreDtos.StoreResponse;
import dev.storeforge.stores.internal.web.StoreDtos.StoreSummaryResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/stores")
class StoresController {

    private final StoreService stores;

    StoresController(StoreService stores) {
        this.stores = stores;
    }

    @PostMapping
    ResponseEntity<StoreResponse> create(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody CreateStoreRequest body) {
        var store = stores.create(user.userId(), body.handle(), body.name(), body.currency());
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + store.id())).body(StoreResponse.from(store));
    }

    @GetMapping
    Map<String, List<StoreSummaryResponse>> mine(@AuthenticationPrincipal UserPrincipal user) {
        return Map.of("data", stores.storesOf(user.userId()).stream()
                .map(store -> new StoreSummaryResponse(store.id(), store.handle(), store.name(), store.status()))
                .toList());
    }

    @GetMapping("/{storeId}")
    StoreResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId) {
        return StoreResponse.from(stores.get(user.userId(), StoreId.of(storeId)));
    }

    @PatchMapping("/{storeId}")
    StoreResponse rename(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                         @Valid @RequestBody RenameStoreRequest body) {
        return StoreResponse.from(stores.rename(user.userId(), StoreId.of(storeId), body.name()));
    }
}
