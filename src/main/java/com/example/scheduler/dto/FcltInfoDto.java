package com.example.scheduler.dto; // 사용하는 패키지 경로에 맞춰 수정해주세요.

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공공개방자원 시설 정보 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FcltInfoDto {

    // 기본키 및 관리 컬럼
    private Long id;                  // 자동 증가 PK
    private LocalDateTime regDate;    // 등록일시
    private LocalDateTime updateDate; // 수정일시

    // 1. 식별자 및 좌표
    private Long fcltSn;              // 시설순번
    private Double lat;               // 위도
    private Double lon;               // 경도

    // 2. 기관 및 시설 기본 정보
    private String mngInstCd;         // 관리기관코드
    private String pbadmsStdInstCd;   // 행정표준기관코드
    private String instNm;            // 기관명
    private String fcltNm;            // 시설명
    private String fcltTypeCd;        // 시설유형코드
    private String fcltTypeNm;        // 시설유형명
    private String fcltTypeFullNm;    // 시설유형전체명

    // 3. 주소 정보
    private String ctpvNm;            // 시도명
    private String sggNm;             // 시군구명
    private String roadNmAddr;        // 도로명주소
    private String lotnoAddr;         // 지번주소
    private String daddr;             // 상세주소

    // 4. 담당자 정보
    private String picDeptNm;         // 담당부서명
    private String picNm;             // 담당자명
    private String picTelno;          // 담당자전화번호
    private String picEml;            // 담당자이메일

    // 5. 이용 정보 및 설명
    private String tcbizDayInfo;      // 휴무일정보
    private String utztnMthdExpln;    // 이용방법설명
    private String utztnTrgtExpln;    // 이용대상설명
    private String acptNopeExpln;     // 수용인원 및 면수 설명
    private String indrSe;            // 실내외구분코드
    private String indrSeExpln;       // 실내외구분명
    private String chagfeeYn;         // 유료여부 (Y/N)
    private String utztnPayExpln;     // 이용요금설명

    // 6. 예약 및 운영 정보
    private String rsvtPsbltyYn;      // 예약가능여부 (Y/N)
    private String rsvtMthdExpln;     // 예약방법설명
    private String siteUrl;           // 홈페이지URL

    // 7. 운영 시간 정보
    private String weekdaysTmSe;      // 평일운영시간구분
    private String weekdaysTmExpln;   // 평일운영시간설명
    private String satTmSe;           // 토요일운영시간구분
    private String satTmExpln;        // 토요일운영시간설명
    private String hldyTmSe;          // 공휴일운영시간구분
    private String hldyTmExpln;       // 공휴일운영시간설명

    // 8. 관리감독 및 상세 예약 방법
    private String sprvsnType;        // 관리감독유형
    private String sprvsnTypeExpln;   // 관리감독유형설명
    private String sprvsnNm;          // 관리감독기관명
    private String rsvtMthdSiteYn;    // 예약방법-웹사이트여부
    private String rsvtMthdTelYn;     // 예약방법-전화여부
    private String rsvtMthdEmlYn;     // 예약방법-이메일여부
    private String rsvtMthdDocYn;     // 예약방법-서류여부
    private String rsvtMthdEtcYn;     // 예약방법-기타여부
    private String rsvtMthdEtcExpln;  // 예약방법-기타설명

    // 9. 기타
    private String files;             // 첨부파일 (JSON String 형태)
}