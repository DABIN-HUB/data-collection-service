package com.wangbin.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties
@EnableAspectJAutoProxy
public class Application {

    public static void main(String[] args) {
        try {
            SpringApplication.run(Application.class, args);
            log.info("Data collection service started successfully.");
        } catch (Exception e) {
            log.error("Data collection service startup failed.", e);
        }
    }
}