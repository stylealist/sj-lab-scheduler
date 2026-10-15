package com.example.scheduler.controller;

import com.example.scheduler.service.EstateGeocodeService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아파트 단지 좌표 채우기(지도용) 수동 트리거.
 *
 * <p>cron 으로 돌리지 않는다 — 한 번 받으면 끝나는 작업이고, 공공 API 하루 한도 때문에
 * <b>사람이 보면서 나눠 돌리는</b> 편이 안전하기 때문이다. 진행은 드라이버 스크립트
 * {@code sj-lab/scripts/estate-geocode.ps1} 가 맡는다(절차는 같은 저장소
 * {@code docs/estate-geocode-workflow.md}).
 *
 * <p>순서
 * <ol>
 *   <li>{@code /scheduler/estate/complex/sync} — 거래 표에서 단지를 긁어 넣는다(좌표 없음)</li>
 *   <li>{@code /scheduler/estate/complex/geocode?limit=200} — 좌표를 채운다(여러 번 호출)</li>
 *   <li>{@code /scheduler/estate/complex/stats} — 진행 상황 확인</li>
 * </ol>
 *
 * <p><b>표가 없으면 500 이 난다</b> — 먼저 {@code sj-lab/db/estate/estate_apt_complex.sql} 을
 * 담당자가 실행해야 한다.
 */
@RestController
public class EstateGeocodeController {

    private static final Logger log = LoggerFactory.getLogger(EstateGeocodeController.class);

    private final EstateGeocodeService geocodeService;

    public EstateGeocodeController(EstateGeocodeService geocodeService) {
        this.geocodeService = geocodeService;
    }

    /** 거래 표 → 단지 마스터. 여러 번 돌려도 좌표는 지워지지 않는다. */
    @GetMapping("/estate/complex/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @RequestParam(required = false) String sggCd) {
        log.info(">> 단지 마스터 동기화 시작 (sggCd={})", sggCd == null ? "전체" : sggCd);
        Map<String, Object> stats = geocodeService.syncComplexes(sggCd);
        log.info(">> 단지 마스터 동기화 완료: {}", stats);
        return ResponseEntity.ok(stats);
    }

    /**
     * 좌표를 받지 못한 단지 {@code limit} 개를 처리한다.
     *
     * <p>응답의 {@code quotaExceeded} 가 true 면 <b>하루 한도에 걸려 중단</b>된 것이다 —
     * 드라이버는 기다렸다가 다시 부른다(건너뛰면 그만큼 구멍이 남는다).
     */
    @GetMapping("/estate/complex/geocode")
    public ResponseEntity<Map<String, Object>> geocode(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String sggCd) {
        int size = Math.max(1, Math.min(limit, 1000));
        Map<String, Object> result = geocodeService.geocodeBatch(size, sggCd);
        log.info(">> 단지 좌표 채우기: {}", result);
        return ResponseEntity.ok(result);
    }

    /** 진행 상황만 본다(호출 한도를 쓰지 않는다). */
    @GetMapping("/estate/complex/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestParam(required = false) String sggCd) {
        return ResponseEntity.ok(Map.of(
                "configured", geocodeService.isConfigured(),
                "stats", geocodeService.stats(sggCd)));
    }
}
