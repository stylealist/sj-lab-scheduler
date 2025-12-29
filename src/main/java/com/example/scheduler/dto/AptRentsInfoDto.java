package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 아파트 전월세 정보 DTO (ai.apt_rents)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AptRentsInfoDto implements Serializable {

    // 1. 기본 아파트 정보
    private String sggCd;           // 지역코드
    private String umdNm;           // 법정동
    private String jibun;           // 지번
    private String aptNm;           // 아파트명
    private String aptSeq;          // 단지 일련번호

    // 2. 면적 및 층 (수치 정보는 숫자 타입으로 변경)
    private Integer buildYear;      // 건축년도 (String -> Integer)
    private Double excluUseAr;      // 전용면적 (String -> Double, 연산 필요시 BigDecimal 권장)
    private Integer floor;          // 층

    // 3. 계약 날짜
    private Integer dealYear;       // 계약년도
    private Integer dealMonth;      // 계약월
    private Integer dealDay;        // 계약일

    // 4. 금액 정보 (단위가 크므로 Long 사용, 콤마 제거된 값)
    private Long deposit;           // 보증금액 (String -> Long)
    private Long monthlyRent;       // 월세금액 (String -> Long)

    // 5. 계약 상세 정보
    private String contractTerm;    // 계약기간
    private String contractType;    // 계약구분
    private String useRRRight;      // 갱신요구권사용
    private Long preDeposit;        // 종전계약보증금 (String -> Long)
    private Long preMonthlyRent;    // 종전계약월세 (String -> Long)

    // 6. 도로명 주소 정보 (매매 DTO와 스타일 통일: roadnm -> roadNm)
    private String roadNm;          // 도로명
    private String roadNmSggCd;     // 도로명시군구코드
    private String roadNmCd;        // 도로명코드
    private String roadNmSeq;       // 도로명일련번호코드
    private String roadNmbCd;       // 도로명지상지하코드
    private String roadNmBonbun;    // 도로명건물본번호코드
    private String roadNmBubun;     // 도로명건물부번호코드

    // 7. 메타 데이터
    private String createdAt;       // 생성일시
}