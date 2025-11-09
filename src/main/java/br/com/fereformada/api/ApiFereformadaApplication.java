package br.com.fereformada.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ApiFereformadaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiFereformadaApplication.class, args);
    }

}
