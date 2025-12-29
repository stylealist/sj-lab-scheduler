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
}
