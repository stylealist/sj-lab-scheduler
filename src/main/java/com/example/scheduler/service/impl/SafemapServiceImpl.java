package com.example.scheduler.service.impl;

import com.example.scheduler.dto.ConvenienceStoreDto;
import com.example.scheduler.dto.GovernmentOfficeInfoDto;
import com.example.scheduler.mapper.SafemapMapper;
import com.example.scheduler.service.SafemapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.scheduler.util.DataTypeUtil.*;
import static org.apache.commons.configuration.PropertyConverter.toBigDecimal;
import static org.apache.commons.configuration.PropertyConverter.toDouble;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Slf4j
public class SafemapServiceImpl implements SafemapService {
    private final SafemapMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    @Transactional
    public int insertConvenienceStore(List<Map<String,Object>> items) throws Exception {
        // 1) Map -> DTO 변환
        List<ConvenienceStoreDto> dtos = convenienceStoreToDtos(items);
        // 2) 같은 배치 내 중복 OBJT_ID 제거(선택)
        dtos = dedupeByKey(dtos, ConvenienceStoreDto::getObjtId);

        if (dtos.isEmpty()) return 0;

        // 3) 너무 길어지지 않게 청크로 나눠 실행 (VALUES가 너무 길면 PG가 싫어함)
        int affected = 0;
        int chunk = 800; // 500~1000 권장
        for (int i = 0; i < dtos.size(); i += chunk) {
            List<ConvenienceStoreDto> sub = dtos.subList(i, Math.min(i + chunk, dtos.size()));
            if (!sub.isEmpty()) {
                affected += mapper.insertConvenienceStore(sub);
            }
        }
        mapper.insertConvenienceStoreGeojson();
        return affected; // 실제 신규 삽입된 건수
    }

    @Override
    public int insertgovernmentOffice(List<Map<String, Object>> items) throws Exception {
        // 1) Map -> DTO 변환
        List<GovernmentOfficeInfoDto> dtos = governmentOfficeToDtos(items);
        // 2) 같은 배치 내 중복 OBJT_ID 제거(선택)
        dtos = dedupeByKey(dtos, GovernmentOfficeInfoDto::getObjtId);

        if (dtos.isEmpty()) return 0;

        // 3) 너무 길어지지 않게 청크로 나눠 실행 (VALUES가 너무 길면 PG가 싫어함)
        int affected = 0;
        int chunk = 800; // 500~1000 권장
        for (int i = 0; i < dtos.size(); i += chunk) {
            List<GovernmentOfficeInfoDto> sub = dtos.subList(i, Math.min(i + chunk, dtos.size()));
            if (!sub.isEmpty()) {
                affected += mapper.insertgovernmentOffice(sub);
            }
        }
        mapper.insertgovernmentOfficeGeojson();
        return affected; // 실제 신규 삽입된 건수
    }

    private List<ConvenienceStoreDto> convenienceStoreToDtos(List<Map<String, Object>> items) throws Exception {
        List<ConvenienceStoreDto> dtos = new ArrayList<>(items.size());
        for (Map<String,Object> m : items) {
            Long objtId = toLong(m.get("OBJT_ID"));
            if (objtId == null) continue;

            String rawJson = objectMapper.writeValueAsString(m);
            dtos.add(ConvenienceStoreDto.builder()
                    .objtId(objtId)
                    .fcltyCd(s(m.get("FCLTY_CD")))
                    .fcltyNm(s(m.get("FCLTY_NM")))
                    .adres(s(m.get("ADRES")))
                    .rnAdres(s(m.get("RN_ADRES")))
                    .sggCd(s(m.get("SGG_CD")))
                    .emdCd(s(m.get("EMD_CD")))
                    .ctprvnCd(s(m.get("CTPRVN_CD")))
                    .telno(s(m.get("TELNO")))
                    .fcltyTy(s(m.get("FCLTY_TY")))
                    .dataYr(toInt(m.get("DATA_YR")))
                    .x(toDecimal(m.get("X")))
                    .y(toDecimal(m.get("Y")))
                    .rawJson(rawJson)
                    .build());
        }
        return dtos;
    }
    private List<GovernmentOfficeInfoDto> governmentOfficeToDtos(List<Map<String, Object>> items) throws Exception {
        List<GovernmentOfficeInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            BigDecimal objtId = toBigDecimal(m.get("OBJT_ID"));
            if (objtId == null) continue;

            String rawJson = objectMapper.writeValueAsString(m);

            dtos.add(GovernmentOfficeInfoDto.builder()
                    .objtId(objtId)
                    .fcltyTy(s(m.get("FCLTY_TY")))
                    .fcltyCd(s(m.get("FCLTY_CD")))
                    .fcltyNm(s(m.get("FCLTY_NM")))
                    .adres(s(m.get("ADRES")))
                    .rnAdres(s(m.get("RN_ADRES")))
                    .telno(s(m.get("TELNO")))
                    .ctprvnCd(s(m.get("CTPRVN_CD")))
                    .sggCd(s(m.get("SGG_CD")))
                    .emdCd(s(m.get("EMD_CD")))
                    .xCoord(toDouble(m.get("X")))
                    .yCoord(toDouble(m.get("Y")))
                    .dataYr(s(m.get("DATA_YR")))
                    .collectedOn(LocalDate.now())
                    .build());
        }
        return dtos;
    }
    private <T, K> List<T> dedupeByKey(List<T> list, Function<T, K> keyExtractor) {
        Map<K, T> uniq = new LinkedHashMap<>();
        for (var d : list) {
            K key = keyExtractor.apply(d);
            if (key != null) {
                uniq.putIfAbsent(key, d);
            }
        }
        return new ArrayList<>(uniq.values());
    }
    // utils

}
