package com.tether.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SyncServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncServiceApplication.class, args);
    }
}
