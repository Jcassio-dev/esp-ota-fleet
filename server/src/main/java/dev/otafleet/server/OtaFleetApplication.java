package dev.otafleet.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OtaFleetApplication {

    public static void main(String[] args) {
        SpringApplication.run(OtaFleetApplication.class, args);
    }
}
