package com.bigteam.btllm.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [범위] P1 #5 GPU admission control — 동시 실행 1건 + 대기열 상한이라는 계약을 검증한다.
 * 실 Ollama/GPU 없이 순수 JDK 동시성 프리미티브(ThreadPoolExecutor)만으로 테스트 가능하다.
 */
class OllamaGenerationQueueTest {

    private OllamaGenerationQueue newQueue(int capacity) {
        OllamaGenerationQueue queue = new OllamaGenerationQueue();
        ReflectionTestUtils.setField(queue, "queueCapacity", capacity);
        return queue;
    }

    @Test
    @DisplayName("submit한 작업이 실제로 실행된다")
    void submittedTaskRuns() throws Exception {
        OllamaGenerationQueue queue = newQueue(4);
        CountDownLatch ran = new CountDownLatch(1);

        queue.submit(ran::countDown);

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("실행 중인 작업이 없으면 대기 순번은 0이다")
    void reportsZeroQueuedAheadWhenIdle() throws Exception {
        OllamaGenerationQueue queue = newQueue(4);
        CountDownLatch ran = new CountDownLatch(1);

        var submission = queue.submit(ran::countDown);

        assertThat(submission.queuedAhead()).isZero();
        ran.await(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("동시 실행 1건을 초과하는 작업은 대기열에 쌓인다")
    void queuesWorkBeyondSingleConcurrentSlot() throws Exception {
        OllamaGenerationQueue queue = newQueue(4);
        CountDownLatch blockFirst = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);

        queue.submit(() -> {
            firstStarted.countDown();
            awaitUninterruptibly(blockFirst);
        });
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // 첫 작업이 아직 GPU 슬롯을 점유 중 → 두 번째 작업은 대기열로 간다.
        var second = queue.submit(() -> {});
        assertThat(second.queuedAhead()).isZero(); // 두 번째 자신은 아직 큐에 없었을 때 스냅샷
        assertThat(queue.queueSize()).isEqualTo(1); // submit 직후 큐에 실제로 쌓여 있음

        blockFirst.countDown();
        second.future().get(2, TimeUnit.SECONDS); // 정리
    }

    @Test
    @DisplayName("동시 실행 1 + 대기열 상한을 모두 채우면 초과 요청은 즉시 거부된다")
    void rejectsWhenQueueFull() throws Exception {
        int capacity = 2;
        OllamaGenerationQueue queue = newQueue(capacity);
        CountDownLatch blockAll = new CountDownLatch(1);
        CountDownLatch runningStarted = new CountDownLatch(1);

        // 1건 실행 중 + capacity(2)건 대기 = 한도 꽉 채움
        queue.submit(() -> {
            runningStarted.countDown();
            awaitUninterruptibly(blockAll);
        });
        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        queue.submit(() -> awaitUninterruptibly(blockAll));
        queue.submit(() -> awaitUninterruptibly(blockAll));

        assertThatThrownBy(() -> queue.submit(() -> {}))
            .isInstanceOf(RejectedExecutionException.class);

        blockAll.countDown();
    }

    @Test
    @DisplayName("대기 중(아직 실행 전)인 작업은 Future#cancel(true)로 취소하면 실행되지 않는다")
    void cancellingQueuedTaskPreventsExecution() throws Exception {
        OllamaGenerationQueue queue = newQueue(4);
        CountDownLatch blockFirst = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        queue.submit(() -> {
            firstStarted.countDown();
            awaitUninterruptibly(blockFirst);
        });
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        var second = queue.submit(() -> secondRan.set(true));
        boolean cancelled = second.future().cancel(true);
        assertThat(cancelled).isTrue();

        blockFirst.countDown();
        Thread.sleep(100); // 취소된 작업이 혹시라도 실행되면 반영될 시간을 준다
        assertThat(secondRan.get()).isFalse();
    }

    @Test
    @DisplayName("실행 중인 작업은 Future#cancel(true)로 인터럽트할 수 있다")
    void cancellingRunningTaskInterruptsIt() throws Exception {
        OllamaGenerationQueue queue = newQueue(4);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interruptedFlag = new AtomicInteger(0);

        var submission = queue.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interruptedFlag.set(1);
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        submission.future().cancel(true);

        Thread.sleep(200);
        assertThat(interruptedFlag.get()).isEqualTo(1);
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
