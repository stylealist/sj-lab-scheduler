package com.example.scheduler.controller;

import com.example.scheduler.service.ApisDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

@Slf4j
@RestController
public class ApisDataSchedulerController {
    @Value("${apikey.apis.busInfo}")
    private String apisBusInfoServiceKey;
    @Value("${apikey.apis.busStopLocation}")
    private String busStopLocationServiceKey;
    private static ApisDataService apisDataService;
    public ApisDataSchedulerController(ApisDataService apisDataService) {
        this.apisDataService = apisDataService;
    }

    /**
     * 국토교통부_(TAGO)_버스노선정보
     */
    @Scheduled(cron = "0 40 00 * * *")
    @RequestMapping("/apis/bus/busRouteInfo")
    public void busRouteInfo() {
        try {
            // 도시코드 목록 조회
            StringBuilder cityUrlBuilder = new StringBuilder("http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getCtyCodeList");
            cityUrlBuilder.append("?serviceKey=").append(apisBusInfoServiceKey);
            cityUrlBuilder.append("&_type=json");

            List<Map<String, Object>> param = busRouteInfoToList(cityUrlBuilder);

            int insertApisBusCityInfo = apisDataService.insertApisBusCityInfo(param);
            System.out.println(param);
            System.out.println(insertApisBusCityInfo);
        } catch (Exception e) {
            log.error("busInfo 수집 실패", e);
        }
    }
    /**
     * 국토교통부_전국 버스 정류장 위치 정보
     */
    @Scheduled(cron = "0 00 01 * * *")
    @RequestMapping("/apis/bus/busStopLocation")
    public void busStopLocation() {
        final int requestPerPage = 10000; // 권장: 1000 (ODcloud 상한)
        final String base = "https://api.odcloud.kr/api/15067528/v1/uddi:ed6d9b4d-96cc-4e2a-85b3-98769395fc87";

        List<Map<String, Object>> totalData = new ArrayList<>();
        try {
            // 1) 첫 페이지
            StringBuilder first = new StringBuilder(base)
                    .append("?serviceKey=").append(URLEncoder.encode(busStopLocationServiceKey, "UTF-8"))
                    .append("&page=1")
                    .append("&perPage=").append(requestPerPage)
                    .append("&returnType=json");

            Map<String, Object> page1 = busStopLocationToList(first);
            if (page1 == null || page1.isEmpty()) {
                log.warn("ODcloud 첫 페이지 응답이 비어 있습니다.");
                return;
            }

            int totalCount   = toInt(page1.get("totalCount"));
            int respPerPage  = toInt(page1.get("perPage"));         // 서버가 실제로 적용한 perPage
            int currentCount = toInt(page1.get("currentCount"));    // 이번 페이지 건수
            if (respPerPage <= 0) respPerPage = currentCount;       // fallback
            if (respPerPage <= 0) respPerPage = getDataSize(page1); // 최종 fallback

            int totalPages = (int) Math.ceil((double) totalCount / respPerPage);

            List<Map<String, Object>> data1 =
                    (List<Map<String, Object>>) page1.getOrDefault("data", Collections.emptyList());
            totalData.addAll(data1);

            log.info("누계: {} / 총 {} (page=1, currentCount={}, respPerPage={})",
                    totalData.size(), totalCount, currentCount, respPerPage);

            // 2) 2 ~ totalPages
            for (int pageNo = 2; pageNo <= totalPages; pageNo++) {
                StringBuilder urlBuilder = new StringBuilder(base)
                        .append("?serviceKey=").append(URLEncoder.encode(busStopLocationServiceKey, "UTF-8"))
                        .append("&page=").append(pageNo)
                        .append("&perPage=").append(requestPerPage) // 요청은 1000으로 고정
                        .append("&returnType=json");

                Map<String, Object> pageN = busStopLocationToList(urlBuilder);
                if (pageN == null || pageN.isEmpty()) break;

                List<Map<String, Object>> dataN =
                        (List<Map<String, Object>>) pageN.getOrDefault("data", Collections.emptyList());
                if (dataN.isEmpty()) break;

                totalData.addAll(dataN);

                log.info("누계: {} / 총 {} (page={}, currentCount={})",
                        totalData.size(), totalCount, pageNo, toInt(pageN.get("currentCount")));
            }
            System.out.println(totalData);

            // 대량이면 여기서 바로 INSERT 말고, 청크(예: 5000개) 단위로 배치 INSERT 권장
             int inserted = apisDataService.insertBusStopLocations(totalData);
            // log.info("총 수집: {}건, 삽입: {}건", total.size(), inserted);

        } catch (Exception e) {
            log.error("busInfo 수집 실패", e);
        }
    }


    private static List<Map<String, Object>> busRouteInfoToList(StringBuilder urlBuilder){
        List<Map<String, Object>> resultData = new ArrayList<>();
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
            Map<String, Object> listMap = new ObjectMapper().readValue(sb.toString(), new TypeReference<Map<String, Object>>() {});
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
