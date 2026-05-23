package com.bigteam.btllm.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing // @WebMvcTest 슬라이스는 @Configuration만 독립 클래스에 있을 때 제외 가능
public class JpaConfig {
}