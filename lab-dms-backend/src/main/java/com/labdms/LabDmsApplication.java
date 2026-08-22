package com.labdms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the Laboratory Document Management System.
 * ISO/IEC 17025 compliant document management with LaTeX versioning.
 */
@SpringBootApplication
// Required for the periodic-review reminder job (ISO 8.3.2 b). Without it the
// @Scheduled method is simply never invoked — silently, with no error.
@EnableScheduling
public class LabDmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabDmsApplication.class, args);
    }
}
