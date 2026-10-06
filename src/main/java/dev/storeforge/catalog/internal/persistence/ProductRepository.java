package dev.storeforge.catalog.internal.persistence;

import dev.storeforge.catalog.internal.domain.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Write side: loads whole aggregates. Lists are served by {@link ProductQueries} (read models). */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** Product + variants in one query (fetch join) instead of 1 + N lazy loads. */
    @EntityGraph(attributePaths = "variants")
    Optional<Product> findByStoreIdAndId(UUID storeId, UUID id);
}
