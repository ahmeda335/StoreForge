package dev.storeforge.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Counts SQL statements prepared through the application's DataSource, so tests can assert that a
 * list endpoint issues a CONSTANT number of queries regardless of page size (the N+1 detector).
 * Wraps the DataSource in a JDK proxy; unwrap()/isWrapperFor() pass through, so Hikari metrics and
 * health checks still see the real pool.
 */
public class QueryCounter implements BeanPostProcessor {

    private static final AtomicLong STATEMENTS = new AtomicLong();

    public static void reset() {
        STATEMENTS.set(0);
    }

    public static long count() {
        return STATEMENTS.get();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && "dataSource".equals(beanName)) {
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                Object result = invoke(dataSource, method, args);
                return result instanceof Connection connection ? countingConnection(connection) : result;
            });
        }
        return bean;
    }

    private static Connection countingConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(QueryCounter.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")
                            || method.getName().equals("prepareCall")) {
                        STATEMENTS.incrementAndGet();
                    }
                    return invoke(connection, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
