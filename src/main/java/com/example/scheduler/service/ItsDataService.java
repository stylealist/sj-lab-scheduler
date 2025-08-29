package com.example.scheduler.service;

import java.util.List;
import java.util.Map;

public interface ItsDataService {
    /** ITS CCTV 데이터 적재 (실제 삽입된 건수 반환) */
    int insertItsCctvInfo(List<Map<String, Object>> cctvDataList) throws Exception;
}
