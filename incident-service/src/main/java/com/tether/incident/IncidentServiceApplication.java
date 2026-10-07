package com.tether.incident;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IncidentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentServiceApplication.class, args);
    }
}
