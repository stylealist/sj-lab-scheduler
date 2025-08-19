package com.example.scheduler.controller;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SafemapScheduler {
    @Value("${apikey.safe}")
    private String safeServiceKey;

    @Scheduled(cron = "0 30 19 * * *", zone = "Asia/Seoul")
    public void ConvenienceStore(){
        log.error("스케쥴링 실행");
    }
}
