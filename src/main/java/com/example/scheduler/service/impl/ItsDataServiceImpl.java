package com.example.scheduler.service.impl;

import com.example.scheduler.dto.CctvInfoDto;
import com.example.scheduler.mapper.ItsDataMapper;
import com.example.scheduler.service.ItsDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.scheduler.util.DataTypeUtil.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItsDataServiceImpl implements ItsDataService {
    private final ItsDataMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    @Transactional
    public int insertItsCctvInfo(List<Map<String, Object>> cctvDataList) throws Exception {
        if (cctvDataList == null || cctvDataList.isEmpty()) return 0;

        // Map -> DTO (필수값: url, lon/lat)
        List<CctvInfoDto> dtos = mapToCctvDtos(cctvDataList);
        if (dtos.isEmpty()) return 0;

        int affected = 0;

        for (CctvInfoDto d : dtos) {
            // 키가 될 세 컬럼이 없으면 스킵
            if (d.getCctvType() == null || d.getCctvFormat() == null || isBlank(d.getCctvName())) {
                continue;
            }
            // 1) 존재여부 확인 (cctv_type, cctv_format, cctv_name 조합)
            int exist = mapper.countCctvByKey(d.getCctvType(), d.getCctvFormat(), d.getCctvName());

            if (exist > 0) {
                // 2) 존재하면 cctv_url만 갱신
                int cnt = mapper.updateCctvUrlByKey(d.getCctvType(), d.getCctvFormat(), d.getCctvName(), d.getCctvUrl());
                affected += cnt;
            } else {
                // 3) 없으면 INSERT (좌표는 4326 -> 3857 변환)
                int cnt = mapper.insertCctvInfoOne(d);
                affected += cnt;
            }
            System.out.println("현재 진행중인 cctv row : " + affected);
        }

        log.info("ITS CCTV affected total: {}", affected);
        return affected;
    }

    private List<CctvInfoDto> mapToCctvDtos(List<Map<String, Object>> items) throws Exception {
        List<CctvInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 필수 키: cctvurl (없으면 스킵)
            String cctvUrl = s(first(m, "cctvurl", "CCTVURL", "cctvUrl"));
            if (isBlank(cctvUrl)) continue;

            // 좌표(WGS84) – 없으면 스킵 (DB 저장 시 3857로 변환 예정)
            Double lonDeg = d(first(m, "coordx", "COORDX", "lon", "longitude", "x"));
            Double latDeg = d(first(m, "coordy", "COORDY", "lat", "latitude", "y"));
            if (lonDeg == null || latDeg == null) continue;

            // 선택 필드들
            String coordType = s(first(m, "coordtype", "coordType"));
            if (isBlank(coordType)) coordType = "WGS84"; // 기본값

            Integer dataCount = i(first(m, "datacount", "dataCount"));

            Integer cctvTypeInt = i(first(m, "cctvtype", "cctvType"));
            Short cctvType = (cctvTypeInt != null ? cctvTypeInt.shortValue() : null);

            CctvInfoDto dto = CctvInfoDto.builder()
                    .coordType(coordType)
                    .dataCount(dataCount)
                    .roadSectionId(s(first(m, "roadsectionid", "roadSectionId")))
                    .fileCreateTime(s(first(m, "filecreatetime", "fileCreateTime")))
                    .cctvType(cctvType)
                    .cctvUrl(cctvUrl)
                    .cctvResolution(s(first(m, "cctvresolution", "cctvResolution")))
                    .lonDeg(lonDeg)   // 4326 경도
                    .latDeg(latDeg)   // 4326 위도
                    .cctvFormat(s(first(m, "cctvformat", "cctvFormat")))
                    .cctvName(s(first(m, "cctvname", "cctvName")))
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }
}
