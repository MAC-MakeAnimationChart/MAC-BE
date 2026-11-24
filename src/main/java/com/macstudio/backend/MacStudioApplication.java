package com.macstudio.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class MacStudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(MacStudioApplication.class, args);
	}

}
