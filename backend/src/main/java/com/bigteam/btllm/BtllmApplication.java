package com.bigteam.btllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing // @CreatedDate, @LastModifiedDate 자동 주입 활성화
public class BtllmApplication {

	public static void main(String[] args) {
		SpringApplication.run(BtllmApplication.class, args);
	}

}
