package com.proovy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class ProovyApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProovyApiApplication.class, args);
	}

}
