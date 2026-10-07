package dev.storeforge.stores.internal.web;

import dev.storeforge.stores.CurrentStorefront;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import dev.storeforge.stores.StoreNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Tenant identification for the public storefront: hostname → store, via the cached store directory.
 *
 * <p>{@code getServerName()} is the Host header, or {@code X-Forwarded-Host} when the request came
 * through our reverse proxy ({@code server.forward-headers-strategy=framework}). Either way the
 * client chooses it, which is fine: it only selects WHICH public storefront to show.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
class StorefrontResolution implements WebMvcConfigurer {

    private final StoreDirectory directory;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(CurrentStorefront.class) && parameter.getParameterType() == StoreInfo.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
                // No MDC here: an argument resolver has no "finally" to remove it again. The storeId
                // log field is set by TenantTransactions for the duration of the tenant transaction.
                return directory.resolveHost(request.getServerName())
                        .filter(StoreInfo::active)
                        .orElseThrow(StoreNotFoundException::new);
            }
        });
    }
}
