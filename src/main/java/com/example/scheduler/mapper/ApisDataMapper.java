package com.example.scheduler.mapper;

import com.example.scheduler.dto.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ApisDataMapper {

    int insertApisBusCityInfo(@Param("list") List<BusCityInfoDto> list);
    List<String> insertApisBusRouteInfo(@Param("list") List<BusRouteInfoDto> list);
    List<BusCityInfoDto> selectApisBusCityInfo();
    List<String> insertApisBusStopInfo(@Param("list") List<BusStopInfoDto> list);
    void insertApisBusStopInfoGeoJson();
    List<String> insertPharmacy(@Param("list") List<PharmacyInfoDto> list);
    void insertApisPharmacyInfoGeoJson();
    List<String> insertHospital(@Param("list") List<HospitalInfoDto> list);
    void insertApisHospitalInfoGeoJson();
    int countAptTrades(AptTradesInfoDto param);
    List<String> insertAptTrades(@Param("list") List<AptTradesInfoDto> list);
    int countAptRents(AptRentsInfoDto param);
    List<String> insertAptRents(@Param("list") List<AptRentsInfoDto> list);
    // ── 아파트 단지 마스터(estate.apt_complex) — 지도용 좌표 ────────────────
    // 실거래 API 는 좌표를 주지 않는다. 단지 4.5만 개만 지오코딩해 apt_seq 로 거래에 붙인다.
    // 표 정의는 sj-lab/db/estate/estate_apt_complex.sql (DDL 은 담당자 실행).

    /** 매매 거래에서 단지를 긁어 넣는다(좌표·상태는 건드리지 않는다). */
    int syncAptComplexFromTrade(@Param("sggCd") String sggCd);

    /** 전월세에만 있는 단지도 넣는다. */
    int syncAptComplexFromRent(@Param("sggCd") String sggCd);

    /** 아직 좌표를 못 받은 단지(거래 많은 순). */
    List<Map<String, Object>> selectPendingComplexes(
            @Param("limit") int limit, @Param("sggCd") String sggCd, @Param("maxTries") int maxTries);

    /** 좌표 저장. **원 좌표계(srid)를 함께 넘긴다** — 행안부 5179, VWorld 4326. */
    int updateComplexCoord(
            @Param("aptSeq") String aptSeq,
            @Param("x") double x,
            @Param("y") double y,
            @Param("srid") int srid,
            @Param("source") String source,
            @Param("accuracy") String accuracy);

    /** 실패·건너뜀 기록(사유는 짧게, 키를 남기지 말 것). */
    int markComplexGeocodeFailed(
            @Param("aptSeq") String aptSeq, @Param("status") String status, @Param("error") String error);

    /**
     * 테두리를 받을 단지(시도·시군구 이름을 함께 준다 — 주소로 물어야 하므로).
     *
     * @param mode {@code "site"} 면 **이번 수집 버전으로 받지 않은 단지**를 고른다(출처 끝에
     *             붙는 버전으로 판단한다). 그 밖에는 종전대로 테두리가 빈 단지만 고른다.
     * @param siteVer 출처에 붙는 수집 버전(예: {@code "_V2"}). 규칙을 고치면 이 값을 올린다.
     */
    List<Map<String, Object>> selectComplexesForBoundary(
            @Param("limit") int limit, @Param("sggCd") String sggCd, @Param("mode") String mode,
            @Param("siteVer") String siteVer, @Param("aptSeq") String aptSeq);

    /**
     * 단지 테두리(연속지적도 필지 폴리곤) 저장. **핀도 그 안쪽 중심으로 옮긴다** —
     * 행안부 좌표는 건물 출입구라 단지 가장자리에 찍힌다.
     */
    int updateComplexBoundary(
            @Param("aptSeq") String aptSeq,
            @Param("pnu") String pnu,
            @Param("parts") java.util.List<String> parts,
            @Param("source") String source);

    /**
     * 단지 <b>대지 영역</b>(지적도 필지) 저장. 건물이 올라 있는 필지만 골라 합친다.
     *
     * @param parcels {@code {"pnu","geom"}} 묶음 목록(주변 필지). 고르는 일은 PostGIS 가 한다.
     * @param buildings 그 단지 건물 GeoJSON 목록(어느 필지가 이 단지 땅인지 판단하는 씨앗).
     */
    int updateComplexSite(
            @Param("aptSeq") String aptSeq,
            @Param("pnu") String pnu,
            @Param("parcels") java.util.List<Map<String, String>> parcels,
            @Param("buildings") java.util.List<String> buildings,
            @Param("rivals") java.util.List<String> rivals,
            @Param("sourceLot") String sourceLot,
            @Param("sourceBldg") String sourceBldg);

    /**
     * "시도했고 이렇게 끝났다"는 표시만 남긴다(테두리·좌표는 그대로).
     *
     * <p>대지로 못 받은 단지를 다음 회차에 또 뽑지 않게 하는 장치다.
     */
    int markComplexBoundarySource(@Param("aptSeq") String aptSeq, @Param("source") String source);

    /** 받아 둔 테두리를 지운다(다시 받기 위해). 좌표는 그대로 둔다. */
    int resetComplexBoundary(@Param("sggCd") String sggCd);

    /** 테두리를 받은 단지 수. **좌표 통계와 분리**한다 — 컬럼이 없는 DB 에서 같이 깨지지 않게. */
    long selectComplexBoundaryCount(@Param("sggCd") String sggCd);

    /** 진행 상황(전체·성공·대기·실패·건너뜀·좌표 보유). */
    Map<String, Object> selectComplexGeocodeStats(@Param("sggCd") String sggCd);

    int insertFcltInfo(@Param("list") List<FcltInfoDto> list);
    void insertFcltInfoGeoJson();
    void createFcltInfoView();
}
