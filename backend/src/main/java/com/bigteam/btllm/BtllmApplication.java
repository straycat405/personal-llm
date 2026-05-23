package com.bigteam.btllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// [설계] @EnableAsync: EtlPipelineService의 @Async 메서드 활성화
//   ETL 인덱싱을 별도 스레드에서 실행 → HTTP 즉시 반환 후 백그라운드 처리
@SpringBootApplication
@EnableAsync
public class BtllmApplication {

	public static void main(String[] args) {
		SpringApplication.run(BtllmApplication.class, args);
	}

}
