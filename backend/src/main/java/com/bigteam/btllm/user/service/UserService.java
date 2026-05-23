package com.bigteam.btllm.user.service;

import com.bigteam.btllm.common.jwt.JwtProvider;
import com.bigteam.btllm.user.dto.LoginRequest;
import com.bigteam.btllm.user.dto.LoginResponse;
import com.bigteam.btllm.user.dto.SignupRequest;
import com.bigteam.btllm.user.entity.User;
import com.bigteam.btllm.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigteam.btllm.common.exception.BusinessException;
import com.bigteam.btllm.common.exception.ErrorCode;

/**
 * [역할] 회원가입·로그인 유스케이스 처리
 *
 * [설계 결정사항]
 * - login()에 @Transactional(readOnly = true) 적용:
 *   쓰기 없는 조회 전용 트랜잭션 → JPA flush 스킵, 커넥션 풀 최적화
 * - User Enumeration 공격 방지:
 *   존재하지 않는 아이디·비밀번호 불일치 동일 메시지 반환
 *   공격자가 유효한 username 목록을 열거하는 것을 방어
 * - 토큰 발급을 Service에서 처리: Controller가 JWT 로직을 알 필요 없음
 */

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public void signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        // [설계] username = 이메일 prefix 자동 생성: OAuth 연동 시 provider name으로 덮어쓰기 가능
        String username = request.email().split("@")[0];
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .username(username)
                .build();
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // [설계] User Enumeration 방지: 이메일 미존재·비밀번호 불일치 동일 메시지
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getEmail());
        return new LoginResponse(accessToken);
    }
}
