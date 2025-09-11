package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Hospital Info DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HospitalInfoDto implements Serializable {
    private Long id;               // PK

    private String hpid;           // 기관ID (A2502719)
    private String dutyName;       // 병원명 (자인치과의원)
    private String dutyAddr;       // 주소
    private String dutyTel1;       // 전화번호

    // 병원 분류 정보
    private String dutyDiv;        // 병원분류 (N)
    private String dutyDivNam;     // 병원분류명 (치과의원)
    private String dutyEmcls;      // 응급의료분류 (G099)
    private String dutyEmclsName;  // 응급의료분류명 (응급의료기관 이외)
    private String dutyEryn;       // 응급실운영여부 (2)

    // 운영시간 (월~일)
    private String dutyTime1s;     // 월요일 시작
    private String dutyTime1c;     // 월요일 종료
    private String dutyTime2s;     // 화요일 시작
    private String dutyTime2c;     // 화요일 종료
    private String dutyTime3s;     // 수요일 시작
    private String dutyTime3c;     // 수요일 종료
    private String dutyTime4s;     // 목요일 시작
    private String dutyTime4c;     // 목요일 종료
    private String dutyTime5s;     // 금요일 시작
    private String dutyTime5c;     // 금요일 종료
    private String dutyTime6s;     // 토요일 시작
    private String dutyTime6c;     // 토요일 종료
    private String dutyTime7s;     // 일요일 시작
    private String dutyTime7c;     // 일요일 종료

    private Double wgs84Lat;       // 위도
    private Double wgs84Lon;       // 경도

    private String postCdn1;       // 우편번호 앞자리
    private String postCdn2;       // 우편번호 뒷자리
    private String rnum;           // 일련번호

    private LocalDate collectedOn; // 수집일
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}