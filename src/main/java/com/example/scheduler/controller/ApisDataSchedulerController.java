package com.example.scheduler.controller;

import com.example.scheduler.dto.BusCityInfoDto;
import com.example.scheduler.dto.SggInfoDto;
import com.example.scheduler.service.ApisDataService;
import com.example.scheduler.service.CommonService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.example.scheduler.util.DataConverter.xmlToList;

@Slf4j
@RestController
@Transactional
public class ApisDataSchedulerController {
    @Value("${apikey.apis}")
    private String apisServiceKey;

    /**
     * 공공데이터포털 호출 사이에 쉬는 시간(ms).
     *
     * <p>예전에는 페이지 사이에만 50ms 를 쉬고 지역 사이에는 쉬지 않아, 짧은 시간에 수백 건을 쏘다가
     * <b>429(호출 과다)</b> 에 걸렸다(2026-10-06 백필 중 실제 발생 — 한 지역에 재시도로 1분씩 쓰다가
     * 결국 건너뛰어 그 달 데이터가 빠졌다). 그래서 간격을 설정값으로 빼고 기본을 넉넉히 둔다.
     * 급할 때만 `-Dapis.request-delay-ms=100` 처럼 낮출 것.
     */
    @Value("${apis.request-delay-ms:300}")
    private long requestDelayMs;

    /**
     * 예비 서비스 키. 주 키의 한도가 떨어졌을 때 대신 쓴다(쉼표로 여러 개도 가능).
     *
     * <p><b>한도는 키마다, 그리고 엔드포인트마다 따로 매겨진다</b>(2026-10-07 확인).
     * 같은 키로 매매는 429 인데 전월세는 200 이 나오고, 다른 키로는 매매도 200 이 나왔다.
     * 그래서 "키가 막혔다"를 전체 차단으로 보면 안 되고, <b>쓰려는 엔드포인트마다</b> 확인해야 한다.
     * 값은 저장소에 적지 말고 환경변수 `APIS_SERVICE_KEY_2` 로 넣을 것(이 저장소는 public).
     */
    @Value("${apikey.apis2:}")
    private String apisServiceKeyAlt;

    /**
     * 이번 수집에서 쓰기로 고른 키. 한 번의 요청(스레드) 안에서만 유효하다.
     * 데일리 cron 과 백필이 동시에 돌 수 있어 인스턴스 필드로 두면 서로 덮어쓴다.
     */
    private final ThreadLocal<String> activeServiceKey = new ThreadLocal<>();

    /** 설정된 키 목록(주 키 → 예비 키). 빈 값·중복은 버린다. */
    private List<String> candidateServiceKeys() {
        List<String> keys = new ArrayList<>();
        for (String raw : (apisServiceKey + "," + apisServiceKeyAlt).split(",")) {
            String key = raw == null ? "" : raw.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 수집을 시작하기 전에 <b>그 엔드포인트에서 실제로 되는 키</b>를 골라 둔다.
     *
     * <p>한 건만 요청해 보고 응답이 오면 그 키를 쓴다. 한도가 떨어진 키는 429 를 주므로 다음 키로 넘어간다.
     * 수백 번 호출하다 중간에 막혀 그 달 데이터가 빠지는 것보다, 시작 전에 1~2번 더 부르는 편이 싸다.
     * 되는 키가 없으면 주 키를 그대로 쓴다 — 그러면 수집이 429 로 건너뛰고, 그 사실이 `skipped` 로 올라가
     * 백필 드라이버가 기다렸다 다시 시도한다(조용히 넘어가지 않는다).
     *
     * @param baseUrl 쓰려는 엔드포인트. 한도가 엔드포인트별이라 반드시 같은 주소로 확인해야 한다
     */
    private void selectServiceKeyFor(String baseUrl) {
        List<String> keys = candidateServiceKeys();
        if (keys.size() <= 1) {
            activeServiceKey.set(keys.isEmpty() ? null : keys.get(0));
            return;
        }

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (probeKey(baseUrl, key)) {
                if (i > 0) {
                    log.warn("주 서비스 키의 한도가 떨어져 예비 키 {}번으로 수집합니다: {}", i + 1, baseUrl);
                } else {
                    log.info("서비스 키 1번으로 수집합니다: {}", baseUrl);
                }
                activeServiceKey.set(key);
                return;
            }
            log.warn("서비스 키 {}번은 이 엔드포인트에서 쓸 수 없습니다(한도 소진 등): {}", i + 1, baseUrl);
        }

        log.error("설정된 키 {}개가 모두 이 엔드포인트에서 막혀 있습니다: {}", keys.size(), baseUrl);
        activeServiceKey.set(keys.get(0));
    }

    /** 키 한 개로 한 건만 요청해 본다. 200 이면 쓸 수 있는 키다. */
    private boolean probeKey(String baseUrl, String key) {
        String probeKeyParam = key.contains("%")
                ? key
                : URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(baseUrl)
                .append("?serviceKey=").append(probeKeyParam)
                .append("&LAWD_CD=11110")
                .append("&DEAL_YMD=").append(LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM")))
                .append("&pageNo=1&numOfRows=1");
        Map<String, Object> response = xmlToList(url);
        return response != null && !response.isEmpty() && response.get("body") != null;
    }

    /** 호출 간격 쉬기. 인터럽트는 삼키지 않고 플래그를 되살려 상위 루프가 멈출 수 있게 한다. */
    private void pauseBetweenCalls() {
        if (requestDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    private static ApisDataService apisDataService;
    private static CommonService commonService;
    public ApisDataSchedulerController(ApisDataService apisDataService,
                                       CommonService commonService) {
        this.apisDataService = apisDataService;
        this.commonService = commonService;
    }
    @RequestMapping("/apis/test")
    public String test() {
        return "test";
    }
    /**
     * 공공데이터포털에 붙일 serviceKey 를 만든다.
     *
     * <p><b>이미 인코딩된 키(% 포함)를 또 인코딩하면 공공데이터포털이 403 을 돌려준다.</b>
     * 실제로 2025-12 에 그렇게 멈췄다 — 키가 인코딩된 형태로 바뀐 뒤, 키를 무조건
     * {@code URLEncoder.encode} 하던 수집(약국·병원·아파트 매매·아파트 전월세)이 전부 403 이 되고,
     * 이 검사를 하던 버스 노선 수집만 계속 동작했다.
     * 그래서 모든 수집이 이 메서드 하나만 쓰도록 모았다. <b>여기 말고 다른 곳에서 키를 인코딩하지 말 것.</b>
     */
    /** 평소 수집 범위 — 이번 달과 지난 달. 실거래 신고가 늦게 들어오므로 지난 달도 다시 받는다. */
    private List<String> defaultMonths() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMM");
        LocalDate now = LocalDate.now();
        return List.of(now.format(formatter), now.minusMonths(1).format(formatter));
    }

    /**
     * 백필용 월 목록(`yyyyMM`)을 만든다. from·to 를 안 주면 {@link #defaultMonths()} 와 같다.
     * to 를 빼면 이번 달까지, from 을 빼면 to 한 달만 받는다.
     * 거꾸로 주면(from > to) 바꿔서 쓰고, 형식이 틀리면 그 자리에서 알려 준다.
     */
    private List<String> monthRange(String from, String to) {
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
            return defaultMonths();
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMM");
        YearMonth start = parseMonth(from, YearMonth.now(), formatter);
        YearMonth end = parseMonth(to, YearMonth.now(), formatter);
        if (start.isAfter(end)) {
            YearMonth swap = start;
            start = end;
            end = swap;
        }
        List<String> months = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            months.add(m.format(formatter));
        }
        return months;
    }

    private YearMonth parseMonth(String raw, YearMonth fallback, DateTimeFormatter formatter) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return YearMonth.parse(raw.trim(), formatter);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("기간은 yyyyMM 형식이어야 합니다(예: 202601). 받은 값: " + raw);
        }
    }

    private String serviceKeyParam() {
        // selectServiceKeyFor() 로 고른 키가 있으면 그것을, 없으면 주 키를 쓴다.
        String key = activeServiceKey.get();
        if (key == null || key.isEmpty()) {
            key = apisServiceKey;
        }
        return key.contains("%")
                ? key
                : URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 공공데이터포털에서 버스노선정보 데이터를 DB에 저장하는 기능
     * 대상 레이어 : (CCTV)
     * 업데이트 시간 : 매일 00시 40분
     */
    @Scheduled(cron = "0 40 00 * * *")
    @RequestMapping("/apis/bus/busRouteInfo")
    public void busRouteInfo() {
        final int pageSize = 1000; // TAGO 권장 최대
        final String cityListBase  = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getCtyCodeList";
        final String routeListBase = "https://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteNoList";

        String keyParam = serviceKeyParam();

        try {
            /* 1) 도시코드 목록 수집 */
            StringBuilder cityUrlBuilder = new StringBuilder(cityListBase)
                    .append("?serviceKey=").append(keyParam)
                    .append("&_type=json");

            List<Map<String, Object>> cityItems = busRouteInfoToList(cityUrlBuilder);
            int insertedCities = apisDataService.insertApisBusCityInfo(cityItems);
            System.out.println("도시코드 수집/삽입: " + cityItems.size() + " / " + insertedCities);

            /* 2) DB에서 도시 목록 조회 */
            List<BusCityInfoDto> cities = apisDataService.selectApisBusCityInfo();

            /* 3) 도시별 노선번호 목록 전량 페이징 수집 */
            List<Map<String, Object>> totalBusRouteInfo = new ArrayList<>();

            for (BusCityInfoDto city : cities) {
                int pageNo = 1;
                while (true) {
                    StringBuilder routeUrlBuilder = new StringBuilder(routeListBase)
                            .append("?serviceKey=").append(keyParam)     // 첫 파라미터는 ?
                            .append("&pageNo=").append(pageNo)           // 이후는 모두 &
                            .append("&numOfRows=").append(pageSize)
                            .append("&cityCode=").append(city.getCityCode())
                            .append("&_type=json");

                    // 기존 파서 그대로 사용: response/body/items/item → List<Map<String,Object>>
                    List<Map<String, Object>> pageItems = busRouteInfoToList(routeUrlBuilder);

                    if (pageItems == null || pageItems.isEmpty()) break;

                    // ⚠️ bus_route_info에 city_code / city_name 컬럼을 넣었으므로, 매 항목에 주입
                    for (Map<String, Object> m : pageItems) {
                        m.put("citycode", city.getCityCode());
                        m.put("CITYCODE", city.getCityCode()); // 매핑 헬퍼가 first(...)로 대소문자 모두 보게 했으면 굳이 둘 다는 필요없지만 안전하게
                        if (city.getCityName() != null) {
                            m.put("cityname", city.getCityName());
                            m.put("CITYNAME", city.getCityName());
                        }
                    }

                    totalBusRouteInfo.addAll(pageItems);

                    System.out.printf("도시 %s(%s) page=%d 수집누계=%d%n",
                            city.getCityName(), city.getCityCode(), pageNo, totalBusRouteInfo.size());

                    if (pageItems.size() < pageSize) break; // 마지막 페이지
                    pageNo++;
                }
            }

            System.out.println("총 노선 개수(누계): " + totalBusRouteInfo.size());

            /* 4) DB 적재: 청크 분할 권장 (예: 5000건) */
            int insertedRoutes = apisDataService.insertApisBusRouteInfo(totalBusRouteInfo);
            System.out.println("노선 삽입 건수: " + insertedRoutes);

        } catch (Exception e) {
            log.error("busRouteInfo 수집 실패", e);
        }
    }

    /**
     * 공공데이터포털에서 전국 약국 정보 데이터를 DB에 저장하는 기능
     * 대상 레이어 : (약국)
     * 업데이트 시간 : 매일 01시 20분
     */
    @Scheduled(cron = "0 20 01 * * *")
    @RequestMapping("/apis/amenities/pharmacy")
    public void pharmacy() {
        final int requestPerPage = 10000; // 요청할 페이지당 건수
        final String base = "https://apis.data.go.kr/B552657/ErmctInsttInfoInqireService/getParmacyFullDown";

        List<Map<String, Object>> totalData = new ArrayList<>();
        try {
            // 1) 첫 페이지 요청
            StringBuilder first = new StringBuilder(base)
                    .append("?serviceKey=").append(serviceKeyParam())
                    .append("&pageNo=1")
                    .append("&numOfRows=").append(requestPerPage);

            Map<String, Object> page1Response = xmlToList(first);
            if (page1Response == null || page1Response.isEmpty()) {
                log.warn("약국 API 첫 페이지 응답이 비어 있습니다.");
                return;
            }

            // XML 응답에서 메타 정보 추출
            Map<String, Object> body = (Map<String, Object>) page1Response.get("body");
            if (body == null) {
                log.warn("응답에서 body를 찾을 수 없습니다.");
                return;
            }

            int totalCount = toInt(body.get("totalCount"));
            int numOfRows = toInt(body.get("numOfRows")); // 실제 요청된 페이지 크기

            // items에서 실제 데이터 추출
            Map<String, Object> items = (Map<String, Object>) body.get("items");
            List<Map<String, Object>> data1 = new ArrayList<>();

            if (items != null) {
                Object itemData = items.get("item");
                if (itemData instanceof List) {
                    data1 = (List<Map<String, Object>>) itemData;
                } else if (itemData instanceof Map) {
                    // item이 하나만 있는 경우
                    data1.add((Map<String, Object>) itemData);
                }
            }

            totalData.addAll(data1);

            // 실제 페이지당 건수 계산 (fallback 로직)
            int respPerPage = numOfRows > 0 ? numOfRows : data1.size();
            if (respPerPage <= 0) respPerPage = requestPerPage; // 최종 fallback

            int totalPages = (int) Math.ceil((double) totalCount / respPerPage);

            log.info("약국 데이터 수집 시작 - 총 {}건, 예상 {}페이지 (첫 페이지: {}건 수집)",
                    totalCount, totalPages, data1.size());
            log.info("누계: {} / 총 {} (page=1, currentCount={}, respPerPage={})",
                    totalData.size(), totalCount, data1.size(), respPerPage);

            // 2) 2페이지부터 마지막 페이지까지 수집
            for (int currentPageNo = 2; currentPageNo <= totalPages; currentPageNo++) {
                StringBuilder urlBuilder = new StringBuilder(base)
                        .append("?serviceKey=").append(serviceKeyParam())
                        .append("&pageNo=").append(currentPageNo)
                        .append("&numOfRows=").append(requestPerPage);

                Map<String, Object> pageNResponse = xmlToList(urlBuilder);
                if (pageNResponse == null || pageNResponse.isEmpty()) {
                    log.warn("페이지 {}의 응답이 비어 있습니다. 수집을 중단합니다.", currentPageNo);
                    break;
                }
                // 응답에서 데이터 추출
                Map<String, Object> pageBody = (Map<String, Object>) pageNResponse.get("body");
                if (pageBody == null) {
                    log.warn("페이지 {}에서 body를 찾을 수 없습니다.", currentPageNo);
                    break;
                }
                Map<String, Object> pageItems = (Map<String, Object>) pageBody.get("items");
                List<Map<String, Object>> dataN = new ArrayList<>();

                if (pageItems != null) {
                    Object itemData = pageItems.get("item");
                    if (itemData instanceof List) {
                        dataN = (List<Map<String, Object>>) itemData;
                    } else if (itemData instanceof Map) {
                        dataN.add((Map<String, Object>) itemData);
                    }
                }

                totalData.addAll(dataN);

                log.info("누계: {} / 총 {} (page={}, currentCount={})",
                        totalData.size(), totalCount, currentPageNo, dataN.size());

                // API 호출 제한을 고려한 대기 시간 (선택사항)
                try {
                    Thread.sleep(100); // 100ms 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("약국 데이터 수집 완료 - 총 {}건 수집", totalData.size());

            System.out.println(totalData);

            int inserted = apisDataService.insertPharmacy(totalData);
        } catch (Exception e) {
            log.error("약국 정보 수집 실패", e);
            throw new RuntimeException("약국 API 데이터 수집 중 오류 발생", e);
        }
    }

    /**
     * 공공데이터포털에서 전국 병원 정보 데이터를 DB에 저장하는 기능
     * 대상 레이어 : (병원)
     * 업데이트 시간 : 매일 01시 40분
     */
    @Scheduled(cron = "0 40 01 * * *")
    @RequestMapping("/apis/amenities/hospital")
    public void hospital() {
        final int requestPerPage = 10000; // 요청할 페이지당 건수
        final String base = "https://apis.data.go.kr/B552657/HsptlAsembySearchService/getHsptlMdcncFullDown";

        List<Map<String, Object>> totalData = new ArrayList<>();
        try {
            // 1) 첫 페이지 요청
            StringBuilder first = new StringBuilder(base)
                    .append("?serviceKey=").append(serviceKeyParam())
                    .append("&pageNo=1")
                    .append("&numOfRows=").append(requestPerPage);

            Map<String, Object> page1Response = xmlToList(first);
            if (page1Response == null || page1Response.isEmpty()) {
                log.warn("병원 API 첫 페이지 응답이 비어 있습니다.");
                return;
            }

            // XML 응답에서 메타 정보 추출
            Map<String, Object> body = (Map<String, Object>) page1Response.get("body");
            if (body == null) {
                log.warn("응답에서 body를 찾을 수 없습니다.");
                return;
            }

            int totalCount = toInt(body.get("totalCount"));
            int numOfRows = toInt(body.get("numOfRows")); // 실제 요청된 페이지 크기

            // items에서 실제 데이터 추출
            Map<String, Object> items = (Map<String, Object>) body.get("items");
            List<Map<String, Object>> data1 = new ArrayList<>();

            if (items != null) {
                Object itemData = items.get("item");
                if (itemData instanceof List) {
                    data1 = (List<Map<String, Object>>) itemData;
                } else if (itemData instanceof Map) {
                    // item이 하나만 있는 경우
                    data1.add((Map<String, Object>) itemData);
                }
            }

            totalData.addAll(data1);

            // 실제 페이지당 건수 계산 (fallback 로직)
            int respPerPage = numOfRows > 0 ? numOfRows : data1.size();
            if (respPerPage <= 0) respPerPage = requestPerPage; // 최종 fallback

            int totalPages = (int) Math.ceil((double) totalCount / respPerPage);

            log.info("병원 데이터 수집 시작 - 총 {}건, 예상 {}페이지 (첫 페이지: {}건 수집)",
                    totalCount, totalPages, data1.size());
            log.info("누계: {} / 총 {} (page=1, currentCount={}, respPerPage={})",
                    totalData.size(), totalCount, data1.size(), respPerPage);

            // 2) 2페이지부터 마지막 페이지까지 수집
            for (int currentPageNo = 2; currentPageNo <= totalPages; currentPageNo++) {
                StringBuilder urlBuilder = new StringBuilder(base)
                        .append("?serviceKey=").append(serviceKeyParam())
                        .append("&pageNo=").append(currentPageNo)
                        .append("&numOfRows=").append(requestPerPage);

                Map<String, Object> pageNResponse = xmlToList(urlBuilder);
                if (pageNResponse == null || pageNResponse.isEmpty()) {
                    log.warn("페이지 {}의 응답이 비어 있습니다. 수집을 중단합니다.", currentPageNo);
                    break;
                }
                // 응답에서 데이터 추출
                Map<String, Object> pageBody = (Map<String, Object>) pageNResponse.get("body");
                if (pageBody == null) {
                    log.warn("페이지 {}에서 body를 찾을 수 없습니다.", currentPageNo);
                    break;
                }
                Map<String, Object> pageItems = (Map<String, Object>) pageBody.get("items");
                List<Map<String, Object>> dataN = new ArrayList<>();

                if (pageItems != null) {
                    Object itemData = pageItems.get("item");
                    if (itemData instanceof List) {
                        dataN = (List<Map<String, Object>>) itemData;
                    } else if (itemData instanceof Map) {
                        dataN.add((Map<String, Object>) itemData);
                    }
                }

                totalData.addAll(dataN);

                log.info("누계: {} / 총 {} (page={}, currentCount={})",
                        totalData.size(), totalCount, currentPageNo, dataN.size());

                // API 호출 제한을 고려한 대기 시간 (선택사항)
                try {
                    Thread.sleep(100); // 100ms 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("병원 데이터 수집 완료 - 총 {}건 수집", totalData.size());

            System.out.println(totalData);

            int inserted = apisDataService.insertHospital(totalData);
        } catch (Exception e) {
            log.error("병원 정보 수집 실패", e);
            throw new RuntimeException("병원 API 데이터 수집 중 오류 발생", e);
        }
    }

    /**
     * 공공데이터포털에서 전국 버스 정류장 위치 정보 데이터를 DB에 저장하는 기능
     * 대상 레이어 : (CCTV)
     * 업데이트 시간 : 매일 01시 00분
     * 기타사항 : 공공데이터포털 스펙 아웃으로 api가 아닌 excel 다운로드해서 갱신해야함
     */
//    @Scheduled(cron = "0 00 01 * * *")
//    @RequestMapping("/apis/bus/busStopLocation")
//    public void busStopLocation() {
//        final int requestPerPage = 10000; // 권장: 1000 (ODcloud 상한)
//        final String base = "https://api.odcloud.kr/api/15067528/v1/uddi:ed6d9b4d-96cc-4e2a-85b3-98769395fc87";
//
//        List<Map<String, Object>> totalData = new ArrayList<>();
//        try {
//            // 1) 첫 페이지
//            StringBuilder first = new StringBuilder(base)
//                    .append("?serviceKey=").append(serviceKeyParam())
//                    .append("&page=1")
//                    .append("&perPage=").append(requestPerPage)
//                    .append("&returnType=json");
//
//            Map<String, Object> page1 = busStopLocationToList(first);
//            if (page1 == null || page1.isEmpty()) {
//                log.warn("ODcloud 첫 페이지 응답이 비어 있습니다.");
//                return;
//            }
//
//            int totalCount   = toInt(page1.get("totalCount"));
//            int respPerPage  = toInt(page1.get("perPage"));         // 서버가 실제로 적용한 perPage
//            int currentCount = toInt(page1.get("currentCount"));    // 이번 페이지 건수
//            if (respPerPage <= 0) respPerPage = currentCount;       // fallback
//            if (respPerPage <= 0) respPerPage = getDataSize(page1); // 최종 fallback
//
//            int totalPages = (int) Math.ceil((double) totalCount / respPerPage);
//
//            List<Map<String, Object>> data1 =
//                    (List<Map<String, Object>>) page1.getOrDefault("data", Collections.emptyList());
//            totalData.addAll(data1);
//
//            log.info("누계: {} / 총 {} (page=1, currentCount={}, respPerPage={})",
//                    totalData.size(), totalCount, currentCount, respPerPage);
//
//            // 2) 2 ~ totalPages
//            for (int pageNo = 2; pageNo <= totalPages; pageNo++) {
//                StringBuilder urlBuilder = new StringBuilder(base)
//                        .append("?serviceKey=").append(serviceKeyParam())
//                        .append("&page=").append(pageNo)
//                        .append("&perPage=").append(requestPerPage) // 요청은 1000으로 고정
//                        .append("&returnType=json");
//
//                Map<String, Object> pageN = busStopLocationToList(urlBuilder);
//                if (pageN == null || pageN.isEmpty()) break;
//
//                List<Map<String, Object>> dataN =
//                        (List<Map<String, Object>>) pageN.getOrDefault("data", Collections.emptyList());
//                if (dataN.isEmpty()) break;
//
//                totalData.addAll(dataN);
//
//                log.info("누계: {} / 총 {} (page={}, currentCount={})",
//                        totalData.size(), totalCount, pageNo, toInt(pageN.get("currentCount")));
//            }
//
//            // 대량이면 여기서 바로 INSERT 말고, 청크(예: 5000개) 단위로 배치 INSERT 권장
//            int inserted = apisDataService.insertBusStopLocations(totalData);
//            // log.info("총 수집: {}건, 삽입: {}건", total.size(), inserted);
//
//        } catch (Exception e) {
//            log.error("busInfo 수집 실패", e);
//        }
//    }     
    /**
     * 공공데이터포털에서 국토교통부_아파트 매매 실거래가 상세 자료 정보 데이터를 DB에 저장하는 기능
     * 대상 데이터 : 아파트 실거래가 속성정보
     * 업데이트 시간 : 매일 06시 00분
     */
    @Scheduled(cron = "0 0 06 * * *")
    public void aptTrades() {
        collectAptTrades(defaultMonths());
    }

    /**
     * 아파트 매매 백필 — 빠진 기간을 손으로 받을 때 쓴다.
     *
     * <pre>
     * GET /scheduler/apis/housing/aptTrades?from=202601&amp;to=202610
     * </pre>
     *
     * 인자를 안 주면 평소(이번 달 + 지난 달)와 같다. 한 번에 긴 기간을 주면
     * (개월 × 251 시군구 × 페이지) 호출이라 공공데이터포털 일일 트래픽 한도에 걸릴 수 있으니
     * <b>몇 달씩 나눠</b> 부를 것. 같은 달을 다시 받아도 유니크 인덱스 + ON CONFLICT 로 중복은 안 쌓인다.
     */
    @RequestMapping("/apis/housing/aptTrades")
    public Map<String, Object> aptTradesBackfill(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        List<String> months = monthRange(from, to);
        log.info("[매매 백필] 대상 {}개월: {}", months.size(), months);
        com.example.scheduler.util.DataConverter.resetSkippedCount();
        int inserted = collectAptTrades(months);
        int skipped = com.example.scheduler.util.DataConverter.skippedCount();
        log.info("[매매 백필] 완료 — 새로 적재 {}건", inserted);
        if (skipped > 0) {
            log.warn("[{}] 호출 한도 등으로 {}건의 요청을 건너뛰었습니다 — 이 구간은 데이터가 빠졌을 수 있습니다.",
                    "aptTrades", skipped);
        }
        return Map.of("dataset", "aptTrades", "months", months,
                "monthCount", months.size(), "inserted", inserted, "skipped", skipped);
    }

    /**
     * @return 이번 호출로 <b>새로 들어간</b> 행 수. 유니크 인덱스 + ON CONFLICT DO NOTHING 때문에
     *         이미 있는 거래는 세지 않는다. 과거로 거슬러 가는 백필에서 "이 구간은 더 받을 게 없다"를
     *         판단하는 기준이 이 값이다(scripts/estate-backfill.ps1).
     */
    private int collectAptTrades(List<String> targetMonths) {
        int insertedTotal = 0;
        final int requestPerPage = 10000;
        final String base = "https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev";

        // 한도는 키마다·엔드포인트마다 따로다. 시작 전에 이 주소에서 되는 키를 골라 둔다.
        selectServiceKeyFor(base);

        try {
            List<SggInfoDto> sggList = commonService.selectSggList();

            // [지역별 루프]
            for (SggInfoDto sggData : sggList) {
                String lawdCd = sggData.getSggCd();

                // 지역별 데이터를 담을 리스트 (메모리 절약을 위해 지역마다 초기화 추천)
                List<Map<String, Object>> sggTotalData = new ArrayList<>();

                // [날짜별 루프] (이번 달 -> 저번 달 순회)
                for (String dealYmd : targetMonths) {

                    // --- 기존 API 호출 로직 시작 ---
                    StringBuilder first = new StringBuilder(base)
                            .append("?serviceKey=").append(serviceKeyParam())
                            .append("&LAWD_CD=").append(lawdCd)
                            .append("&DEAL_YMD=").append(dealYmd) // 수정된 날짜 변수 사용
                            .append("&pageNo=1")
                            .append("&numOfRows=").append(requestPerPage);

                    Map<String, Object> page1Response = xmlToList(first);

                    // 데이터가 없거나 에러인 경우 다음 날짜로 넘어감
                    if (page1Response == null || page1Response.isEmpty()) {
                        continue;
                    }

                    Map<String, Object> body = (Map<String, Object>) page1Response.get("body");
                    if (body == null) continue;

                    int totalCount = toInt(body.get("totalCount"));
                    int numOfRows = toInt(body.get("numOfRows"));

                    // items 추출
                    Map<String, Object> items = (Map<String, Object>) body.get("items");
                    List<Map<String, Object>> data1 = new ArrayList<>();

                    if (items != null) {
                        Object itemData = items.get("item");
                        if (itemData instanceof List) {
                            data1 = (List<Map<String, Object>>) itemData;
                        } else if (itemData instanceof Map) {
                            data1.add((Map<String, Object>) itemData);
                        }
                    }

                    sggTotalData.addAll(data1);

                    // 페이지 계산
                    int respPerPage = numOfRows > 0 ? numOfRows : data1.size();
                    if (respPerPage <= 0) respPerPage = requestPerPage;
                    int totalPages = (int) Math.ceil((double) totalCount / respPerPage);

                    log.info("[{}] 지역({}) - {} 데이터 수집: 총 {}건 (예상 페이지: {})",
                            sggData.getSggNm(), lawdCd, dealYmd, totalCount, totalPages);

                    // 2페이지부터 수집
                    for (int currentPageNo = 2; currentPageNo <= totalPages; currentPageNo++) {
                        StringBuilder urlBuilder = new StringBuilder(base)
                                .append("?serviceKey=").append(serviceKeyParam())
                                .append("&LAWD_CD=").append(lawdCd)
                                .append("&DEAL_YMD=").append(dealYmd)
                                .append("&pageNo=").append(currentPageNo)
                                .append("&numOfRows=").append(requestPerPage);

                        Map<String, Object> pageNResponse = xmlToList(urlBuilder);
                        if (pageNResponse == null) break;

                        Map<String, Object> pageBody = (Map<String, Object>) pageNResponse.get("body");
                        if (pageBody == null) break;

                        Map<String, Object> pageItems = (Map<String, Object>) pageBody.get("items");
                        if (pageItems == null) break;

                        List<Map<String, Object>> dataN = new ArrayList<>();
                        Object itemData = pageItems.get("item");
                        if (itemData instanceof List) {
                            dataN = (List<Map<String, Object>>) itemData;
                        } else if (itemData instanceof Map) {
                            dataN.add((Map<String, Object>) itemData);
                        }

                        sggTotalData.addAll(dataN);

                        pauseBetweenCalls();
                    }
                } // [날짜 루프 종료]

                // [중요] 한 지역(SGG)의 수집이 끝나면 바로 DB에 넣고 메모리 해제
                if (!sggTotalData.isEmpty()) {
                    log.info(">> DB 저장 시작: 지역코드 {} (건수: {})", lawdCd, sggTotalData.size());
                    insertedTotal += apisDataService.insertAptTrades(sggTotalData);
                    sggTotalData.clear(); // 메모리 확보
                }

                // 지역을 넘길 때도 쉰다 — 전에는 여기서 안 쉬어 429 에 걸렸다
                pauseBetweenCalls();

            } // [지역 루프 종료]

        } catch (Exception e) {
            log.error("아파트 매매 정보 수집 실패", e);
            throw new RuntimeException("아파트 매매 API 데이터 수집 중 오류 발생", e);
        } finally {
            activeServiceKey.remove();
        }
        return insertedTotal;
    }
    /**
     * 공공데이터포털에서 국토교통부_아파트 전월세 자료 데이터를 DB에 저장하는 기능
     * 대상 데이터 : 아파트 전월세 자료
     * 업데이트 시간 : 매일 06시 00분
     */
    @Scheduled(cron = "0 0 07 * * *")
    public void aptRents() {
        collectAptRents(defaultMonths());
    }

    /**
     * 아파트 전월세 백필. 사용법과 주의는 {@link #aptTradesBackfill(String, String)} 와 같다.
     *
     * <pre>
     * GET /scheduler/apis/housing/aptRents?from=202601&amp;to=202610
     * </pre>
     */
    @RequestMapping("/apis/housing/aptRents")
    public Map<String, Object> aptRentsBackfill(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        List<String> months = monthRange(from, to);
        log.info("[전월세 백필] 대상 {}개월: {}", months.size(), months);
        com.example.scheduler.util.DataConverter.resetSkippedCount();
        int inserted = collectAptRents(months);
        int skipped = com.example.scheduler.util.DataConverter.skippedCount();
        log.info("[전월세 백필] 완료 — 새로 적재 {}건", inserted);
        if (skipped > 0) {
            log.warn("[{}] 호출 한도 등으로 {}건의 요청을 건너뛰었습니다 — 이 구간은 데이터가 빠졌을 수 있습니다.",
                    "aptRents", skipped);
        }
        return Map.of("dataset", "aptRents", "months", months,
                "monthCount", months.size(), "inserted", inserted, "skipped", skipped);
    }

    /** @return 새로 들어간 행 수. 자세한 설명은 {@link #collectAptTrades(List)} 참고. */
    private int collectAptRents(List<String> targetMonths) {
        int insertedTotal = 0;
        final int requestPerPage = 10000; // 요청할 페이지당 건수
        final String base = "https://apis.data.go.kr/1613000/RTMSDataSvcAptRent/getRTMSDataSvcAptRent";

        // 한도는 키마다·엔드포인트마다 따로다. 시작 전에 이 주소에서 되는 키를 골라 둔다.
        selectServiceKeyFor(base);

        try {
            List<SggInfoDto> sggList = commonService.selectSggList();

            // [지역별 루프]
            for (SggInfoDto sggData : sggList) {
                String lawdCd = sggData.getSggCd();

                // 지역별 데이터를 담을 리스트 (메모리 절약을 위해 지역마다 초기화)
                List<Map<String, Object>> sggTotalData = new ArrayList<>();

                // [날짜별 루프] (이번 달 -> 저번 달 순회)
                for (String dealYmd : targetMonths) {

                    // --- API 호출 로직 ---
                    StringBuilder first = new StringBuilder(base)
                            .append("?serviceKey=").append(serviceKeyParam())
                            .append("&LAWD_CD=").append(lawdCd)
                            .append("&DEAL_YMD=").append(dealYmd)
                            .append("&pageNo=1")
                            .append("&numOfRows=").append(requestPerPage);

                    Map<String, Object> page1Response = xmlToList(first);

                    // 데이터가 없거나 에러인 경우 다음 날짜로 넘어감
                    if (page1Response == null || page1Response.isEmpty()) {
                        continue;
                    }

                    Map<String, Object> body = (Map<String, Object>) page1Response.get("body");
                    if (body == null) continue;

                    int totalCount = toInt(body.get("totalCount"));
                    int numOfRows = toInt(body.get("numOfRows"));

                    // items 추출
                    Map<String, Object> items = (Map<String, Object>) body.get("items");
                    List<Map<String, Object>> data1 = new ArrayList<>();

                    if (items != null) {
                        Object itemData = items.get("item");
                        if (itemData instanceof List) {
                            data1 = (List<Map<String, Object>>) itemData;
                        } else if (itemData instanceof Map) {
                            data1.add((Map<String, Object>) itemData);
                        }
                    }

                    sggTotalData.addAll(data1);

                    // 페이지 계산
                    int respPerPage = numOfRows > 0 ? numOfRows : data1.size();
                    if (respPerPage <= 0) respPerPage = requestPerPage;
                    int totalPages = (int) Math.ceil((double) totalCount / respPerPage);

                    log.info("[전월세] {} 지역({}) - {} 데이터 수집: 총 {}건 (예상 페이지: {})",
                            sggData.getSggNm(), lawdCd, dealYmd, totalCount, totalPages);

                    // 2페이지부터 수집
                    for (int currentPageNo = 2; currentPageNo <= totalPages; currentPageNo++) {
                        StringBuilder urlBuilder = new StringBuilder(base)
                                .append("?serviceKey=").append(serviceKeyParam())
                                .append("&LAWD_CD=").append(lawdCd)
                                .append("&DEAL_YMD=").append(dealYmd)
                                .append("&pageNo=").append(currentPageNo)
                                .append("&numOfRows=").append(requestPerPage);

                        Map<String, Object> pageNResponse = xmlToList(urlBuilder);
                        if (pageNResponse == null) break;

                        Map<String, Object> pageBody = (Map<String, Object>) pageNResponse.get("body");
                        if (pageBody == null) break;

                        Map<String, Object> pageItems = (Map<String, Object>) pageBody.get("items");
                        if (pageItems == null) break;

                        List<Map<String, Object>> dataN = new ArrayList<>();
                        Object itemData = pageItems.get("item");
                        if (itemData instanceof List) {
                            dataN = (List<Map<String, Object>>) itemData;
                        } else if (itemData instanceof Map) {
                            dataN.add((Map<String, Object>) itemData);
                        }

                        sggTotalData.addAll(dataN);

                        pauseBetweenCalls();
                    }
                } // [날짜 루프 종료]

                // [중요] 한 지역(SGG)의 데이터 수집이 끝나면 DB 저장
                if (!sggTotalData.isEmpty()) {
                    log.info(">> DB 저장 시작 (전월세): 지역코드 {} (건수: {})", lawdCd, sggTotalData.size());
                    // [변경] 전월세 전용 Insert 메서드 호출
                    insertedTotal += apisDataService.insertAptRents(sggTotalData);
                    sggTotalData.clear();
                }

                // 지역을 넘길 때도 쉰다 — 전에는 여기서 안 쉬어 429 에 걸렸다
                pauseBetweenCalls();

            } // [지역 루프 종료]

        } catch (Exception e) {
            log.error("아파트 전월세 정보 수집 실패", e);
            throw new RuntimeException("아파트 전월세 API 데이터 수집 중 오류 발생", e);
        } finally {
            activeServiceKey.remove();
        }
        return insertedTotal;
    }

    /**
     * 공공데이터포털에서 재정경제부_공공기관 시설정보 자료 데이터를 DB에 저장하는 기능
     * 대상 데이터 : 공공기관 시설정보
     * 업데이트 시간 : 매일 08시 00분
     */
    @RequestMapping("/apis/fclt/list")
    //@Scheduled(cron = "0 0 08 * * *")
    public void fcltList() throws Exception {
        final int requestPerPage = 1000; // API 최대 요청 제한 건수
        final String base = "https://apis.data.go.kr/1051000/fclt/list";

        List<Map<String, Object>> allFcltItems = new ArrayList<>(); // 전체 데이터를 담을 리스트
        int pageNo = 1;

        while (true) {
            StringBuilder fcltList = new StringBuilder(base)
                    .append("?serviceKey=").append(apisServiceKey)
                    .append("&pageNo=").append(pageNo)
                    .append("&numOfRows=").append(requestPerPage)
                    .append("&resultType=").append("json");
            // 페이지별 데이터 조회
            List<Map<String, Object>> fcltItems = fcltInfoToList(fcltList);
            // 가져온 데이터가 없거나 비어있으면 반복 종료
            if (fcltItems == null || fcltItems.isEmpty()) { break; }
            // 전체 리스트에 누적
            allFcltItems.addAll(fcltItems);
            // 요청한 최대 건수(1000건)보다 적게 들어왔다면 마지막 페이지이므로 종료
            if (fcltItems.size() < requestPerPage) { break; }
            // 다음 페이지로 이동
            pageNo++;
        }
        // 최종적으로 전체 데이터(allCityItems)를 활용하는 로직 구현
        log.info("총 수집된 데이터 건수: " + allFcltItems.size());

        if (!allFcltItems.isEmpty()) {
            apisDataService.insertFcltInfo(allFcltItems);
            allFcltItems.clear();
        }

    }

    private static List<Map<String, Object>> fcltInfoToList(StringBuilder urlBuilder) {
        List<Map<String, Object>> resultData = new ArrayList<>();
        try {
            System.out.println("Request URL: " + urlBuilder.toString());

            URI uri = new URI(urlBuilder.toString());
            URL url = uri.toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            // JSON 파싱
            Map<String, Object> listMap = new ObjectMapper().readValue(sb.toString(), new TypeReference<Map<String, Object>>() {});

            // 실제 JSON 구조("result")에 맞춰 데이터 추출
            if (listMap.containsKey("result") && listMap.get("result") != null) {
                resultData = (List<Map<String, Object>>) listMap.get("result");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return resultData;
    }

    private static List<Map<String, Object>> busRouteInfoToList(StringBuilder urlBuilder) {
        List<Map<String, Object>> resultData = new ArrayList<>();
        try {
            System.out.println("Request URL: " + urlBuilder.toString());

            URI uri = new URI(urlBuilder.toString());
            URL url = uri.toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            Map<String, Object> listMap = new ObjectMapper().readValue(sb.toString(), new TypeReference<>() {});
            Map<String, Object> apisData = (Map<String, Object>) listMap.get("response");
            Map<String, Object> body = (Map<String, Object>) apisData.get("body");

            Map<String, Object> items = (Map<String, Object>) body.get("items");
            resultData = (List<Map<String, Object>>) items.get("item");
        } catch (Exception e){
            e.printStackTrace();
        }
        return resultData;
    }
    private static Map<String, Object> busStopLocationToList(StringBuilder urlBuilder){
        Map<String, Object> resultData = new HashMap<>();
        try{
            System.out.println("Request URL: " + urlBuilder.toString());

            URI uri = new URI(urlBuilder.toString());
            URL url = uri.toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            resultData = new ObjectMapper().readValue(sb.toString(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e){
            e.printStackTrace();
        }
        return resultData;
    }

    private static int toInt(Object o) {
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignore) { return 0; }
    }
    @SuppressWarnings("unchecked")
    private static int getDataSize(Map<String, Object> page) {
        Object data = page.get("data");
        if (data instanceof List) return ((List<?>) data).size();
        return 0;
    }



}
