package com.bigteam.btllm.user.repository;

import com.bigteam.btllm.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

	// 로그인 시 username으로 사용자 조회
	Optional<User> findByUsername(String username);

	// username 중복 체크
	boolean existsByUsername(String username);
}