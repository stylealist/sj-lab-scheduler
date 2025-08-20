package com.example.scheduler.service.impl;

import com.example.scheduler.dto.ConvenienceStoreDto;
import com.example.scheduler.mapper.SafemapMapper;
import com.example.scheduler.service.SafemapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<ConvenienceStoreDto> dtos = mapToDtos(items);
        // 2) 같은 배치 내 중복 OBJT_ID 제거(선택)
        dtos = dedupeByObjtId(dtos);

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
        return affected; // 실제 신규 삽입된 건수
    }

    private List<ConvenienceStoreDto> mapToDtos(List<Map<String, Object>> items) throws Exception {
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
    private List<ConvenienceStoreDto> dedupeByObjtId(List<ConvenienceStoreDto> list) {
        Map<Long, ConvenienceStoreDto> uniq = new LinkedHashMap<>();
        for (var d : list) if (d.getObjtId()!=null) uniq.putIfAbsent(d.getObjtId(), d);
        return new ArrayList<>(uniq.values());
    }
    // utils
    private String s(Object o){ return o==null? null : String.valueOf(o).trim(); }
    private Integer toInt(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:Integer.valueOf(s);}catch(Exception e){return null;}}
    private Long toLong(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:Long.valueOf(s);}catch(Exception e){return null;}}
    private BigDecimal toDecimal(Object o){ try{ var s=String.valueOf(o); return (s==null||s.isBlank())?null:new BigDecimal(s);}catch(Exception e){return null;}}


}
