package com.example.interviewreader.upgradeconsole;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UpgradeConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(UpgradeConsoleApplication.class, args);
    }
}
