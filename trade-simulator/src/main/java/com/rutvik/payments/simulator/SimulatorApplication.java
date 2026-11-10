package com.rutvik.payments.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SimulatorApplication.class, args)));
    }
}
