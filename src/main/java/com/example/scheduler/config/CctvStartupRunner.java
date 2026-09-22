package com.example.scheduler.config;

import com.example.scheduler.controller.ItsDataSchedulerController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * CCTV 스트리밍 URL은 주기적으로 다시 받아야 재생이 되므로, 매일 06:00 cron만으로는
 * 서버를 새로 띄운 시점부터 다음 06:00까지 CCTV 레이어가 비어 있거나 오래된 상태로 남는다.
 * 서버가 기동될 때마다 {@link ItsDataSchedulerController#cctvInfo()}를 한 번 더 실행해 맞춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CctvStartupRunner {

    private final ItsDataSchedulerController itsDataSchedulerController;

    @EventListener(ApplicationReadyEvent.class)
    public void fetchCctvOnStartup() {
        log.info("서버 기동 - CCTV 데이터 최초 수집을 백그라운드에서 시작합니다.");
        // 기동을 막지 않도록 별도 스레드에서 실행. 프록시(itsDataSchedulerController)를 통해 호출해야
        // 클래스에 걸린 @Transactional이 cron 트리거 때와 동일하게 적용된다(self-invocation 금지).
        new Thread(itsDataSchedulerController::cctvInfo, "cctv-startup-fetch").start();
    }
}
