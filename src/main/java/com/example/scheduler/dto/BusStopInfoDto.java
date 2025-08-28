package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Bus Stop DTO
 * 매핑: map.bus_stop 테이블
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusStopInfoDto implements Serializable {
    private Long id;               // PK, 자동 증가 ID (시퀀스 기반)

    private String cityMgmtName;   // 관리도시명 (예: 안동BIS)
    private String cityName;       // 도시명 (예: 경상북도 안동시)
    private Integer cityCode;      // 도시코드
    private Integer mobileShortNo; // 모바일 단축번호

    private String stopName;       // 정류장명 (예: 길안정류장)
    private String stopCode;       // 정류장번호 (예: ADB354000001)

    private Double lon;            // 경도 (128.89122800)
    private Double lat;            // 위도 (36.45865800)

    private LocalDate collectedOn; // 정보수집일 (2024-10-28)

    private LocalDateTime createdAt; // 생성일
    private LocalDateTime updatedAt; // 수정일
}
