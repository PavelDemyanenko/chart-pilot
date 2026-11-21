package com.chartpilot.agui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AguiAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AguiAdapterApplication.class, args);
    }
}