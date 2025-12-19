package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 버스 도시 정보 DTO (map.bus_city_info)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AptTradesInfoDto implements Serializable {
    private Long id;                // PK
    // 1. 문자열 (varchar -> String)
    private String sggCd;           // 시군구 코드
    private String umdCd;           // 읍면동 코드
    private String landCd;          // 대지 코드
    private String bonbun;          // 본번
    private String bubun;           // 부번
    private String jibun;           // 지번
    private String umdNm;           // 읍면동명
    private String roadNm;          // 도로명
    private String roadNmSggCd;     // 도로명 시군구 코드
    private String roadNmCd;        // 도로명 코드
    private String roadNmSeq;       // 도로명 일련번호
    private String roadNmbCd;     // 도로명 지하/지상 코드 (이미지상 road_nmb_cd 오타 가능성 체크, road_nm_nmb_cd로 가정)
    private String roadNmBonbun;    // 도로명 본번
    private String roadNmBubun;     // 도로명 부번
    private String aptNm;           // 아파트명
    private String aptDong;         // 동
    private String aptSeq;          // 아파트 일련번호

    // 2. 정수형 (int4 -> Integer)
    private Integer buildYear;      // 건축년도

    // 3. 실수형 (numeric -> BigDecimal or Double)
    // 정확한 연산을 위해 BigDecimal 권장, 단순 조회용이면 Double도 무관
    private BigDecimal excluUseAr;  // 전용면적

    // 4. 정수형 (int4 -> Integer)
    private Integer floor;          // 층
    private Integer dealYear;       // 계약년도
    private Integer dealMonth;      // 계약월
    private Integer dealDay;        // 계약일

    // 5. 큰 정수형 (int8 -> Long)
    // 금액은 단위가 크므로 반드시 Long 사용
    private Long dealAmount;        // 거래금액

    // 6. 문자열 (varchar -> String)
    private String dealingGbn;      // 거래유형
    private String cdealType;       // 중개거래유형 (직거래/중개)
    private String cdealDay;        // 해제사유 발생일 (DB가 varchar이므로 String)
    private String estateAgentSggNm;// 중개사 소재지
    private String slerGbn;         // 매도자 구분
    private String buyerGbn;        // 매수자 구분
    private String rgstDate;        // 등기일자 (DB가 varchar이므로 String)
    private String landLeaseholdGbn;// 토지임대부 구분

    // 7. 날짜시간 (timestamp -> LocalDateTime)
    private String createdAt; // 생성일시
}



