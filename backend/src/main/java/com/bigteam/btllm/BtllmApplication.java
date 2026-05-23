package com.bigteam.btllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
public class BtllmApplication {

	public static void main(String[] args) {
		SpringApplication.run(BtllmApplication.class, args);
	}

}
