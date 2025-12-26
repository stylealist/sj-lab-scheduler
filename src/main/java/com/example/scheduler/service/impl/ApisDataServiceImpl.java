package com.example.scheduler.service.impl;
import com.example.scheduler.dto.*;
import com.example.scheduler.mapper.ApisDataMapper;
import com.example.scheduler.service.ApisDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.Local;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.scheduler.util.DataTypeUtil.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApisDataServiceImpl implements ApisDataService {
    private final ApisDataMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    @Transactional
    public int insertApisBusCityInfo(List<Map<String, Object>> item) throws Exception {
        List<BusCityInfoDto> dtos = mapToBusCityDtos(item);

        int inserted = mapper.insertApisBusCityInfo(dtos);

        System.out.println("Inserted rows: " + inserted);
        return inserted;
    }
    @Override
    @Transactional
    public int insertApisBusRouteInfo(List<Map<String, Object>> item) throws Exception {
        // 1) Map → DTO
        List<BusRouteInfoDto> dtos = mapToBusRouteDtos(item);
        if (dtos == null || dtos.isEmpty()) return 0;

        // 2) 대용량 대비: 5,000 행씩 분할(파라미터 한도 ~65k 보호)
        final int batchSize = 5000;
        int inserted = 0;

        for (int i = 0; i < dtos.size(); i += batchSize) {
            List<BusRouteInfoDto> chunk = dtos.subList(i, Math.min(i + batchSize, dtos.size()));

            // 3) Mapper 호출: INSERT ... ON CONFLICT DO NOTHING RETURNING route_id
            //    → 실제 삽입된 route_id 리스트만 반환됨(중복은 반환 X)
            List<String> insertedIds = mapper.insertApisBusRouteInfo(chunk); // @Param("list") 사용
            inserted += (insertedIds != null ? insertedIds.size() : 0);
        }

        System.out.println("Inserted rows: " + inserted);
        return inserted;
    }

    @Override
    @Transactional
    public List<BusCityInfoDto> selectApisBusCityInfo() throws Exception {
        return mapper.selectApisBusCityInfo();
    }

    @Override
    @Transactional
    public int insertBusStopLocations(List<Map<String, Object>> item) throws Exception {
        // 1) Map → DTO 변환
        List<BusStopInfoDto> dtos = mapToBusStopDtos(item);
        if (dtos == null || dtos.isEmpty()) return 0;

        // 2) 파라미터 한도 회피: 안전하게 5,000행씩 분할
        final int batchSize = 5000;
        int inserted = 0;

        for (int i = 0; i < dtos.size(); i += batchSize) {
            List<BusStopInfoDto> chunk = dtos.subList(i, Math.min(i + batchSize, dtos.size()));

            // 3) Mapper 호출 (RETURNING stop_code → 실제 삽입된 코드 목록 수)
            // 방법 A: @Param("list") 사용 시
            List<String> insertedCodes = mapper.insertApisBusStopInfo(chunk);

            // 방법 B: parameterType=map로 딱 맞추려면 아래처럼
            // List<String> insertedCodes = mapper.insertApisBusStopInfo(
            //         Collections.singletonMap("list", chunk));

            inserted += (insertedCodes != null ? insertedCodes.size() : 0);
            System.out.println("Inserted rows: " + inserted);
        }
        mapper.insertApisBusStopInfoGeoJson();

        System.out.println("Inserted rows: " + inserted);
        return inserted; // ✅ 실제 삽입된 건수
    }

    @Override
    @Transactional
    public int insertPharmacy(List<Map<String, Object>> item) throws Exception {
        // 1) Map → DTO 변환
        List<PharmacyInfoDto> dtos = mapToPharmacyDtos(item);
        if (dtos == null || dtos.isEmpty()) return 0;

        // 2) 파라미터 한도 회피: 안전하게 5,000행씩 분할
        final int batchSize = 2000;
        int inserted = 0;

        for (int i = 0; i < dtos.size(); i += batchSize) {
            List<PharmacyInfoDto> chunk = dtos.subList(i, Math.min(i + batchSize, dtos.size()));

            // 3) Mapper 호출 (RETURNING stop_code → 실제 삽입된 코드 목록 수)
            // 방법 A: @Param("list") 사용 시
            List<String> insertedCodes = mapper.insertPharmacy(chunk);

            inserted += (insertedCodes != null ? insertedCodes.size() : 0);
            System.out.println("Inserted rows: " + inserted);
        }
        mapper.insertApisPharmacyInfoGeoJson();

        System.out.println("Inserted rows: " + inserted);
        return inserted; // ✅ 실제 삽입된 건수
    }

    @Override
    @Transactional
    public int insertHospital(List<Map<String, Object>> item) throws Exception {
        // 1) Map → DTO 변환
        List<HospitalInfoDto> dtos = mapToHospitalDtos(item);
        if (dtos == null || dtos.isEmpty()) return 0;

        // 2) 파라미터 한도 회피: 안전하게 5,000행씩 분할
        final int batchSize = 2000;
        int inserted = 0;

        for (int i = 0; i < dtos.size(); i += batchSize) {
            List<HospitalInfoDto> chunk = dtos.subList(i, Math.min(i + batchSize, dtos.size()));

            // 3) Mapper 호출 (RETURNING stop_code → 실제 삽입된 코드 목록 수)
            // 방법 A: @Param("list") 사용 시
            List<String> insertedCodes = mapper.insertHospital(chunk);

            inserted += (insertedCodes != null ? insertedCodes.size() : 0);
            System.out.println("Inserted rows: " + inserted);
        }
        mapper.insertApisHospitalInfoGeoJson();

        System.out.println("Inserted rows: " + inserted);
        return inserted; // ✅ 실제 삽입된 건수
    }

    @Override
    public int insertAptTrades(List<Map<String, Object>> item) throws Exception {
        if (item == null || item.isEmpty()) return 0;

        // [중요] 전체 30일 치 데이터를 모을 바구니를 반복문 '밖'에 만듭니다.
        List<AptTradesInfoDto> allDataToInsert = new ArrayList<>();

        LocalDate today = LocalDate.now();
        int minusDay = 30; // 30일 전부터 검사

        // [STEP 1] 30일간 루프를 돌며 넣을 데이터를 수집(Collect)
        while (minusDay > 0) {
            // 날짜 계산
            LocalDate targetDate = today.minusDays(minusDay);
            int targetYear = targetDate.getYear();
            int targetMonth = targetDate.getMonthValue();
            int targetDay = targetDate.getDayOfMonth();

            // 반복 횟수 줄이기 (맨 끝이나 맨 처음에 해도 되지만 누락 주의)
            minusDay--;

            // 1-1. DB에 해당 날짜 데이터가 있는지 확인
            AptTradesInfoDto aptTradesInfoDto = new AptTradesInfoDto();
            aptTradesInfoDto.setDealYear(targetYear);
            aptTradesInfoDto.setDealMonth(targetMonth);
            aptTradesInfoDto.setDealDay(targetDay);

            int count = mapper.countAptTrades(aptTradesInfoDto);

            // [핵심] 데이터가 이미 있으면(count > 0) 이 날짜는 건너뜀(Skip)
            if (count > 0) {
                System.out.println(targetDate + " 데이터는 이미 존재하여 건너뜁니다.");
                continue; // break가 아니라 continue를 써야 다음 날짜로 넘어갑니다.
            }

            // 1-2. 전체 리스트(item)에서 해당 날짜 데이터만 걸러내기
            List<Map<String, Object>> filteredItems = item.stream()
                    .filter(map -> {
                        int y = toInt(map.get("dealYear"));
                        int m = toInt(map.get("dealMonth"));
                        int d = toInt(map.get("dealDay"));
                        return y == targetYear && m == targetMonth && d == targetDay;
                    })
                    .collect(Collectors.toList());

            // 해당 날짜에 API 데이터도 없다면 다음 날짜로 진행
            if (filteredItems.isEmpty()) {
                continue;
            }

            // 1-3. DTO 변환 후 '전체 바구니'에 담기
            List<AptTradesInfoDto> dailyDtos = mapToAptTradesDtos(filteredItems);
            if (dailyDtos != null && !dailyDtos.isEmpty()) {
                allDataToInsert.addAll(dailyDtos); // 여기서 차곡차곡 쌓습니다.
            }
        }

        // [STEP 2] 모인 데이터가 하나도 없으면 종료
        if (allDataToInsert.isEmpty()) {
            System.out.println("저장할 새로운 데이터가 없습니다.");
            return 0;
        }

        // [STEP 3] 배치 Insert (모아둔 데이터를 500개씩 잘라서 저장)
        final int batchSize = 500;
        int inserted = 0;

        for (int i = 0; i < allDataToInsert.size(); i += batchSize) {
            List<AptTradesInfoDto> chunk = allDataToInsert.subList(i, Math.min(i + batchSize, allDataToInsert.size()));

            List<String> insertedCodes = mapper.insertAptTrades(chunk);
            inserted += (insertedCodes != null ? insertedCodes.size() : 0);

            System.out.println("Inserted rows (batch): " + (insertedCodes != null ? insertedCodes.size() : 0));
        }

        System.out.println("Total Inserted rows: " + inserted);
        return inserted;
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
    private List<BusStopInfoDto> mapToBusStopDtos(List<Map<String, Object>> items) throws Exception {
        List<BusStopInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 필수(PK 비슷한 개념) 키: stop_code
            String stopCode = s(first(m, "STOP_CODE", "stop_code", "stopCode", "정류장번호"));
            if (isBlank(stopCode)) continue;

            BusStopInfoDto dto = BusStopInfoDto.builder()
                    .id(l(first(m, "ID", "id"))) // 시퀀스 값 (없을 수도 있음)

                    .cityMgmtName(s(first(m, "CITY_MGMT_NAME", "city_mgmt_name", "cityMgmtName", "관리도시명")))
                    .cityName(s(first(m, "CITY_NAME", "city_name", "cityName", "도시명")))
                    .cityCode(i(first(m, "CITY_CODE", "city_code", "cityCode", "도시코드")))
                    .mobileShortNo(i(first(m, "MOBILE_SHORT_NO", "mobile_short_no", "mobileShortNo", "모바일단축번호")))

                    .stopName(s(first(m, "STOP_NAME", "stop_name", "stopName", "정류장명")))
                    .stopCode(stopCode)

                    .lon(d(first(m, "LON", "lon", "longitude", "경도")))
                    .lat(d(first(m, "LAT", "lat", "latitude", "위도")))

                    .collectedOn(toLocalDate(first(m, "COLLECTED_ON", "collected_on", "collectedOn", "정보수집일")))

                    .createdAt(toLocalDateTime(first(m, "CREATED_AT", "created_at", "createdAt")))
                    .updatedAt(toLocalDateTime(first(m, "UPDATED_AT", "updated_at", "updatedAt")))
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }
    @SuppressWarnings("unchecked")
    private List<BusRouteInfoDto> mapToBusRouteDtos(List<Map<String, Object>> items) {
        List<BusRouteInfoDto> dtos = new ArrayList<>(items.size());
        for (Map<String,Object> m : items) {
            String routeId  = s(first(m, "ROUTEID","routeid","routeId"));
            String cityCode = s(first(m, "CITYCODE","citycode","city_code"));
            if (isBlank(routeId) || isBlank(cityCode)) continue;

            dtos.add(BusRouteInfoDto.builder()
                    .cityCode(cityCode)
                    .cityName(s(first(m, "CITYNAME","cityname","city_name")))   // 응답에 없으면 null → 이후 조인/백필 가능
                    .routeId(routeId)
                    .routeNo(s(first(m, "ROUTENO","routeno","routeNo")))
                    .routeTp(s(first(m, "ROUTETP","routetp","routeTp")))
                    .startNodeNm(s(first(m, "STARTNODENM","startnodenm","startNodeNm")))
                    .endNodeNm(s(first(m, "ENDNODENM","endnodenm","endNodeNm")))
                    .startVehicleTime(toHHmm(first(m,"STARTVEHICLETIME","startvehicletime","startVehicleTime")))
                    .endVehicleTime(toHHmm(first(m,"ENDVEHICLETIME","endvehicletime","endVehicleTime")))
                    .build());
        }
        return dtos;
    }
    private List<PharmacyInfoDto> mapToPharmacyDtos(List<Map<String, Object>> items) throws Exception {
        List<PharmacyInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 필수 키: hpid
            String hpid = s(first(m, "HPID", "hpid"));
            if (isBlank(hpid)) continue;

            PharmacyInfoDto dto = PharmacyInfoDto.builder()
                    .id(l(first(m, "ID", "id")))

                    .hpid(hpid)
                    .dutyName(s(first(m, "DUTY_NAME", "dutyName")))
                    .dutyAddr(s(first(m, "DUTY_ADDR", "dutyAddr")))
                    .dutyTel1(s(first(m, "DUTY_TEL1", "dutyTel1")))
                    .dutyFax(s(first(m, "DUTY_FAX", "dutyFax")))

                    .dutyTime1s(s(first(m, "DUTY_TIME1S", "dutyTime1s")))
                    .dutyTime1c(s(first(m, "DUTY_TIME1C", "dutyTime1c")))
                    .dutyTime2s(s(first(m, "DUTY_TIME2S", "dutyTime2s")))
                    .dutyTime2c(s(first(m, "DUTY_TIME2C", "dutyTime2c")))
                    .dutyTime3s(s(first(m, "DUTY_TIME3S", "dutyTime3s")))
                    .dutyTime3c(s(first(m, "DUTY_TIME3C", "dutyTime3c")))
                    .dutyTime4s(s(first(m, "DUTY_TIME4S", "dutyTime4s")))
                    .dutyTime4c(s(first(m, "DUTY_TIME4C", "dutyTime4c")))
                    .dutyTime5s(s(first(m, "DUTY_TIME5S", "dutyTime5s")))
                    .dutyTime5c(s(first(m, "DUTY_TIME5C", "dutyTime5c")))
                    .dutyTime6s(s(first(m, "DUTY_TIME6S", "dutyTime6s")))
                    .dutyTime6c(s(first(m, "DUTY_TIME6C", "dutyTime6c")))

                    .wgs84Lat(d(first(m, "WGS84_LAT", "wgs84Lat")))
                    .wgs84Lon(d(first(m, "WGS84_LON", "wgs84Lon")))

                    .postCdn1(s(first(m, "POST_CDN1", "postCdn1")))
                    .postCdn2(s(first(m, "POST_CDN2", "postCdn2")))
                    .rnum(s(first(m, "RNUM", "rnum")))

                    .collectedOn(toLocalDate(first(m, "COLLECTED_ON", "collected_on", "collectedOn")))
                    .createdAt(toLocalDateTime(first(m, "CREATED_AT", "created_at", "createdAt")))
                    .updatedAt(toLocalDateTime(first(m, "UPDATED_AT", "updated_at", "updatedAt")))
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }
    private List<HospitalInfoDto> mapToHospitalDtos(List<Map<String, Object>> items) throws Exception {
        List<HospitalInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 필수 키: hpid
            String hpid = s(first(m, "HPID", "hpid"));
            if (isBlank(hpid)) continue;

            HospitalInfoDto dto = HospitalInfoDto.builder()
                    .id(l(first(m, "ID", "id")))

                    .hpid(hpid)
                    .dutyName(s(first(m, "DUTY_NAME", "dutyName")))
                    .dutyAddr(s(first(m, "DUTY_ADDR", "dutyAddr")))
                    .dutyTel1(s(first(m, "DUTY_TEL1", "dutyTel1")))

                    .dutyDiv(s(first(m, "DUTY_DIV", "dutyDiv")))
                    .dutyDivNam(s(first(m, "DUTY_DIV_NAM", "dutyDivNam")))
                    .dutyEmcls(s(first(m, "DUTY_EMCLS", "dutyEmcls")))
                    .dutyEmclsName(s(first(m, "DUTY_EMCLS_NAME", "dutyEmclsName")))
                    .dutyEryn(s(first(m, "DUTY_ERYN", "dutyEryn")))

                    .dutyTime1s(s(first(m, "DUTY_TIME1S", "dutyTime1s")))
                    .dutyTime1c(s(first(m, "DUTY_TIME1C", "dutyTime1c")))
                    .dutyTime2s(s(first(m, "DUTY_TIME2S", "dutyTime2s")))
                    .dutyTime2c(s(first(m, "DUTY_TIME2C", "dutyTime2c")))
                    .dutyTime3s(s(first(m, "DUTY_TIME3S", "dutyTime3s")))
                    .dutyTime3c(s(first(m, "DUTY_TIME3C", "dutyTime3c")))
                    .dutyTime4s(s(first(m, "DUTY_TIME4S", "dutyTime4s")))
                    .dutyTime4c(s(first(m, "DUTY_TIME4C", "dutyTime4c")))
                    .dutyTime5s(s(first(m, "DUTY_TIME5S", "dutyTime5s")))
                    .dutyTime5c(s(first(m, "DUTY_TIME5C", "dutyTime5c")))
                    .dutyTime6s(s(first(m, "DUTY_TIME6S", "dutyTime6s")))
                    .dutyTime6c(s(first(m, "DUTY_TIME6C", "dutyTime6c")))
                    .dutyTime7s(s(first(m, "DUTY_TIME7S", "dutyTime7s")))
                    .dutyTime7c(s(first(m, "DUTY_TIME7C", "dutyTime7c")))

                    .wgs84Lat(d(first(m, "WGS84_LAT", "wgs84Lat")))
                    .wgs84Lon(d(first(m, "WGS84_LON", "wgs84Lon")))

                    .postCdn1(s(first(m, "POST_CDN1", "postCdn1")))
                    .postCdn2(s(first(m, "POST_CDN2", "postCdn2")))
                    .rnum(s(first(m, "RNUM", "rnum")))

                    .collectedOn(toLocalDate(first(m, "COLLECTED_ON", "collected_on", "collectedOn")))
                    .createdAt(toLocalDateTime(first(m, "CREATED_AT", "created_at", "createdAt")))
                    .updatedAt(toLocalDateTime(first(m, "UPDATED_AT", "updated_at", "updatedAt")))
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }

    private List<AptTradesInfoDto> mapToAptTradesDtos(List<Map<String, Object>> items) {
        // items가 null이면 빈 리스트 반환 (안전 처리)
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }

        List<AptTradesInfoDto> dtos = new ArrayList<>(items.size());

        for (Map<String, Object> m : items) {
            // 1. 필수값 체크 (DataTypeUtil.s, first, isBlank 사용)
            String aptNm = s(first(m, "aptNm", "apt_nm"));
            if (isBlank(aptNm)) continue;

            // 2. 거래금액 콤마 제거 처리 (중요!)
            // API가 "82,500" 처럼 콤마를 줄 수 있는데, DataTypeUtil.l()은 이를 숫자로 인식 못함
            String dealAmountStr = s(first(m, "dealAmount", "deal_amount"));
            if (dealAmountStr != null) {
                dealAmountStr = dealAmountStr.replace(",", "");
            }

            // 3. 빌더 패턴으로 DTO 생성
            AptTradesInfoDto dto = AptTradesInfoDto.builder()
                    // --- 문자열 (String) ---
                    .sggCd(s(first(m, "sggCd", "sgg_cd")))
                    .umdCd(s(first(m, "umdCd", "umd_cd")))
                    .landCd(s(first(m, "landCd", "land_cd")))
                    .bonbun(s(first(m, "bonbun")))
                    .bubun(s(first(m, "bubun")))
                    .jibun(s(first(m, "jibun")))

                    .umdNm(s(first(m, "umdNm", "umd_nm")))
                    .roadNm(s(first(m, "roadNm", "road_nm")))
                    .roadNmSggCd(s(first(m, "roadNmSggCd", "road_nm_sgg_cd")))
                    .roadNmCd(s(first(m, "roadNmCd", "road_nm_cd")))
                    .roadNmSeq(s(first(m, "roadNmSeq", "road_nm_seq")))
                    // DTO 필드명이 roadNmbCd 인지 roadNmNmbCd 인지 확인 필요 (여기서는 roadNmbCd로 가정)
                    .roadNmbCd(s(first(m, "roadNmbCd", "road_nmb_cd")))
                    .roadNmBonbun(s(first(m, "roadNmBonbun", "road_nm_bonbun")))
                    .roadNmBubun(s(first(m, "roadNmBubun", "road_nm_bubun")))

                    .aptNm(aptNm)
                    .aptDong(s(first(m, "aptDong", "apt_dong")))
                    .aptSeq(s(first(m, "aptSeq", "apt_seq")))

                    .dealingGbn(s(first(m, "dealingGbn", "dealing_gbn")))
                    .cdealType(s(first(m, "cdealType", "cdeal_type")))
                    .cdealDay(s(first(m, "cdealDay", "cdeal_day")))
                    .estateAgentSggNm(s(first(m, "estateAgentSggNm", "estate_agent_sgg_nm")))
                    .slerGbn(s(first(m, "slerGbn", "sler_gbn")))
                    .buyerGbn(s(first(m, "buyerGbn", "buyer_gbn")))
                    .rgstDate(s(first(m, "rgstDate", "rgst_date")))
                    .landLeaseholdGbn(s(first(m, "landLeaseholdGbn", "land_leasehold_gbn")))

                    // --- 정수형 (Integer) ---
                    // DataTypeUtil.i() 사용
                    .buildYear(i(first(m, "buildYear", "build_year")))
                    .floor(i(first(m, "floor")))
                    .dealYear(i(first(m, "dealYear", "deal_year")))
                    .dealMonth(i(first(m, "dealMonth", "deal_month")))
                    .dealDay(i(first(m, "dealDay", "deal_day")))

                    // --- 금액 (Long) ---
                    // 위에서 콤마 제거한 dealAmountStr 사용 + DataTypeUtil.l() 사용
                    .dealAmount(l(dealAmountStr))

                    // --- 면적 (BigDecimal) ---
                    // ★ bd 대신 DataTypeUtil.decimal() 사용
                    .excluUseAr(decimal(first(m, "excluUseAr", "exclu_use_ar")))

                    // --- 날짜/시간 ---
                    .createdAt(String.valueOf(LocalDateTime.now())) // 수집 시간
                    .build();

            dtos.add(dto);
        }
        return dtos;
    }

}
