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

    /** 진행 상황(전체·성공·대기·실패·건너뜀·좌표 보유). */
    Map<String, Object> selectComplexGeocodeStats(@Param("sggCd") String sggCd);

    int insertFcltInfo(@Param("list") List<FcltInfoDto> list);
    void insertFcltInfoGeoJson();
    void createFcltInfoView();
}
