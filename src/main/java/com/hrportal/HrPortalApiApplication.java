package com.hrportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HrPortalApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrPortalApiApplication.class, args);
    }
}
