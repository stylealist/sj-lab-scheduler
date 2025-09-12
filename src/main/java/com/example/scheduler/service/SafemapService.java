package com.example.scheduler.service;

import java.util.List;
import java.util.Map;

public interface SafemapService {
    int insertConvenienceStore(List<Map<String,Object>> items) throws Exception;
    int insertgovernmentOffice(List<Map<String,Object>> items) throws Exception;
}
