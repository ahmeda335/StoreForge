/**
 * Customers: the people who buy from ONE store. Not platform users: a customer record never
 * crosses stores (the same email in two stores is two unrelated customers).
 *
 * <p>Public API: {@link dev.storeforge.customers.Customers}. P7 has guest customers only
 * (identified by email at checkout); storefront customer accounts are a later addition.
 */
@ApplicationModule(displayName = "Customers")
package dev.storeforge.customers;

import org.springframework.modulith.ApplicationModule;
