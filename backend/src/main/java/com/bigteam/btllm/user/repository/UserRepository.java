package com.bigteam.btllm.user.repository;

import com.bigteam.btllm.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

	// 로그인 시 email로 사용자 조회 (이메일 로그인 방식)
	Optional<User> findByEmail(String email);

	// email 중복 체크
	boolean existsByEmail(String email);
}