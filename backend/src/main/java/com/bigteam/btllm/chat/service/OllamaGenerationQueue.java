package com.bigteam.btllm.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * [역할] Ollama 채팅 생성 요청을 물리 GPU 슬롯 수(RTX 4060 Ti 8GB 단일 슬롯 = 1)에 맞춰
 * 직렬화하는 admission-control 계층.
 *
 * [배경 — HANDOFF 2026-08-27 보수 진단 4-1]
 * 기존 코드는 WebSocket 메시지마다 독립적으로 `.subscribe()`를 호출하고 반환값(Disposable)을
 * 버렸다. 그 결과 (1) 한 세션이 여러 요청을 동시에 시작할 수 있었고, (2) 연결이 끊겨도 이미
 * 시작된 Ollama 호출이 백그라운드에서 계속 GPU를 점유했다. 부하실험에서 동시 2~3명이면
 * 대부분 30초를 넘겼는데, `QUEUED` 응답은 사용자 안내 문구일 뿐 서버가 실제로 순서를
 * 강제하지는 않았다.
 *
 * [설계 결정사항]
 * - `ThreadPoolExecutor(core=max=1, bounded queue)`를 그대로 쓴다 — GPU가 물리적으로 1개뿐이라
 *   "동시성 1, 대기열 상한, 초과 시 즉시 거부(AbortPolicy 기본값)"라는 요구사항과 정확히 일치한다.
 *   커스텀 스케줄러를 만드는 대신 JDK 표준 부품으로 충분하다.
 * - 실행 로직은 Runnable로 받는다: 호출부(ChatWebSocketHandler)가 Reactor Flux를
 *   `.blockLast()`로 이 워커 스레드 안에서 소비하도록 작성해야 동시성 1이 실제로 강제된다.
 *   `.subscribe()`(비동기)로 넘기면 워커 스레드가 즉시 반환되어 버려 직렬화 의미가 없어진다.
 * - `Future.cancel(true)`로 대기 중인 작업(실행 전 취소)과 실행 중인 작업(스레드 인터럽트 →
 *   Reactor의 blockLast()가 이를 감지해 구독을 취소) 모두 취소할 수 있다.
 * - 상용 provider(Claude/OpenAI/Gemini)는 이 큐를 거치지 않는다 — 물리적으로 공유하는 자원이
 *   아니므로 로컬 GPU 슬롯 제약으로 불필요하게 직렬화하면 상용 provider 사용자만 손해다.
 */
@Slf4j
@Component
public class OllamaGenerationQueue {

    // [설계] 대기열 상한을 설정으로 뺀 이유: 하드웨어가 바뀌거나 체감 대기시간 튜닝이 필요할 때
    //   재배포 없이 조정할 수 있게 한다. 기본값 8은 "몇 초~몇 분 안에 순서가 돌아오는 규모"로
    //   실측 없이 잡은 보수적 시작값이다 — 운영 관찰 후 조정 대상이다.
    @Value("${btllm.gpu.queue-capacity:8}")
    private int queueCapacity;

    private volatile ThreadPoolExecutor executor;

    private ThreadPoolExecutor executor() {
        // [설계] @Value가 생성자 시점엔 아직 주입되지 않으므로 지연 초기화한다.
        ThreadPoolExecutor result = executor;
        if (result == null) {
            synchronized (this) {
                result = executor;
                if (result == null) {
                    result = new ThreadPoolExecutor(
                        1, 1,
                        0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueCapacity));
                    executor = result;
                }
            }
        }
        return result;
    }

    public record Submission(Future<?> future, int queuedAhead) {}

    /**
     * @throws RejectedExecutionException 대기열(동시 실행 1 + 대기 {@code queueCapacity})이
     *   가득 찼을 때 — 호출부는 이를 잡아 사용자에게 "나중에 다시 시도" 안내를 보내야 한다.
     */
    public Submission submit(Runnable task) {
        ThreadPoolExecutor pool = executor();
        // [설계] 순번은 근사치다 — 조회와 submit 사이에 다른 작업이 끼어들 수 있다(레이스).
        //   정확한 순번 보장이 아니라 "대기 중임을 사용자에게 알리는" UX 목적이라 허용한다.
        int queuedAhead = pool.getQueue().size();
        Future<?> future = pool.submit(task);
        return new Submission(future, queuedAhead);
    }

    /** 관측/테스트용 — 현재 대기 중인(실행 전) 작업 수. */
    public int queueSize() {
        return executor().getQueue().size();
    }
}
