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

    /**
     * 단지 영역을 {@code limit} 개 채운다.
     *
     * <p>좌표를 받은 뒤에 덧씌우는 <b>2차 패스</b>다. 브이월드 키가 없으면 아무것도 바꾸지 않고
     * "키 없음"만 답한다.
     *
     * <p><b>{@code mode=site}(기본)는 대지(지적도 필지)</b>를 영역으로 쓴다 — 건물만 받으면 그
     * 지번에 선 동들만 그려져 여러 지번에 걸친 단지가 실제의 몇 분의 일로 나왔다(2026-10-21
     * 실측 파크리오 1만㎡ / 실제 23만㎡대). 이 모드는 건물로 받아 둔 단지도 다시 고른다.
     * {@code mode=bldg} 를 주면 종전처럼 건물 외곽으로만 받는다(비교용).
     */
    @GetMapping("/estate/complex/boundary")
    public ResponseEntity<Map<String, Object>> boundary(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String sggCd,
            @RequestParam(defaultValue = "site") String mode,
            @RequestParam(required = false) String aptSeq) {
        int size = Math.max(1, Math.min(limit, 1000));
        Map<String, Object> result = geocodeService.boundaryBatch(size, sggCd, mode, aptSeq);
        log.info(">> 단지 테두리 받기: {}", result);
        return ResponseEntity.ok(result);
    }

    /** 받아 둔 테두리를 지운다(기준이 바뀌어 다시 받을 때). **좌표는 지우지 않는다.** */
    @GetMapping("/estate/complex/boundary/reset")
    public ResponseEntity<Map<String, Object>> boundaryReset(
            @RequestParam(required = false) String sggCd) {
        int cleared = geocodeService.resetBoundaries(sggCd);
        log.info(">> 단지 테두리 초기화: {}건", cleared);
        return ResponseEntity.ok(Map.of("cleared", cleared));
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
