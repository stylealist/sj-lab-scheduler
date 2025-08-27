package com.example.scheduler.service;

import java.util.List;
import java.util.Map;

public interface ApisDataService {
    int insertApisBusCityInfo(List<Map<String,Object>> item) throws Exception;
    int insertBusStopLocations(List<Map<String,Object>> item) throws Exception;

}
