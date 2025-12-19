package com.example.scheduler.service;

import com.example.scheduler.dto.BusCityInfoDto;

import java.util.List;
import java.util.Map;

public interface ApisDataService {
    int insertApisBusCityInfo(List<Map<String,Object>> item) throws Exception;
    int insertApisBusRouteInfo(List<Map<String,Object>> item) throws Exception;
    List<BusCityInfoDto> selectApisBusCityInfo() throws Exception;
    int insertBusStopLocations(List<Map<String,Object>> item) throws Exception;
    int insertPharmacy(List<Map<String,Object>> item) throws Exception;
    int insertHospital(List<Map<String,Object>> item) throws Exception;
    int insertAptTrades(List<Map<String,Object>> item) throws Exception;

}
