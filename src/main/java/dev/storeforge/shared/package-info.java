/**
 * Shared kernel: small, stable building blocks every module may use
 * (error model, request correlation, later Money / StoreId / tenant context).
 *
 * <p>Declared OPEN so its sub-packages are usable by all modules. Keep it free of
 * business logic: anything domain-specific belongs in the owning module.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package dev.storeforge.shared;

import org.springframework.modulith.ApplicationModule;
