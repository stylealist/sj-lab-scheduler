package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Government Office Info DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernmentOfficeInfoDto implements Serializable {
    private Long id;                   // PK

    private BigDecimal objtId;         // 일련번호
    private String fcltyTy;            // 시설분류
    private String fcltyCd;            // 시설코드
    private String fcltyNm;            // 시설명

    // 주소 정보
    private String adres;              // 지번주소
    private String rnAdres;            // 도로명주소
    private String telno;              // 전화번호

    // 행정구역 코드
    private String ctprvnCd;           // 시도코드
    private String sggCd;              // 시군구코드
    private String emdCd;              // 읍면동코드

    // 위치 정보
    private Double xCoord;             // X좌표
    private Double yCoord;             // Y좌표

    // 기타
    private String dataYr;             // 데이터기준

    private LocalDate collectedOn;     // 수집일
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}