package com.example.scheduler.service.impl;

import com.example.scheduler.dto.BusCityInfoDto;
import com.example.scheduler.dto.ConvenienceStoreDto;
import com.example.scheduler.mapper.ApisDataMapper;
import com.example.scheduler.service.ApisDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApisDataServiceImpl implements ApisDataService {
    private final ApisDataMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public int insertApisBusCityInfo(List<Map<String, Object>> item) throws Exception {
        List<BusCityInfoDto> dtos = mapToBusCityDtos(item);
        mapper.insertApisBusCityInfo(dtos);
        return 0;
    }

    @Override
    public int insertBusStopLocations(List<Map<String, Object>> item) throws Exception {
        System.out.println(item);
        return 0;
    }

    private List<BusCityInfoDto> mapToBusCityDtos(List<Map<String, Object>> items) throws Exception {
        List<BusCityInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 필수(PK) 키: city_code
            String cityCode = s(first(m, "CITY_CODE", "city_code", "citycode", "CITYCODE"));
            if (isBlank(cityCode)) continue;

            BusCityInfoDto dto = BusCityInfoDto.builder()
                    .resultCode(s(first(m, "RESULT_CODE", "result_code", "resultCode")))
                    .resultMsg(s(first(m, "RESULT_MSG", "result_msg", "resultMsg")))
                    .cityCode(cityCode)
                    .cityName(s(first(m, "CITY_NAME", "city_name", "cityname", "CITYNAME")))
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }

    /* ===== Helper methods ===== */

    private static Object first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String s(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
