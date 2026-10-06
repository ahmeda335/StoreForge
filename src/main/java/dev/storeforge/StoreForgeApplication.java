package dev.storeforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StoreForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreForgeApplication.class, args);
    }
}
