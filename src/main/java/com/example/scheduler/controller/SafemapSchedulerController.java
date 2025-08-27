package com.example.scheduler.controller;

import com.example.scheduler.service.SafemapService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class SafemapSchedulerController {
    @Value("${apikey.safe}")
    private String safeServiceKey;

    private static SafemapService safemapService;

    public SafemapSchedulerController(SafemapService safemapService) {
        this.safemapService = safemapService;
    }

    //@RequestMapping("/store")
    @Scheduled(cron = "0 20 00 * * *")
    @RequestMapping("/safe-map/convenience-store")
    public void convenienceStore() {
        final int pageSize = 2000;
        List<Map<String, Object>> totalList = new ArrayList<>();
        try {
            String url = "http://safemap.go.kr/openApiService/data/getConvenienceStoreData.do"
                    + "?" + URLEncoder.encode("serviceKey","UTF-8") + "=" + URLEncoder.encode(safeServiceKey, "UTF-8") // 값도 인코딩!
                    + "&" + URLEncoder.encode("numOfRows","UTF-8") + "=" + pageSize
                    + "&" + URLEncoder.encode("dataType","UTF-8") + "=json"
                    + "&" + URLEncoder.encode("Fclty_Cd","UTF-8") + "=509010"
                    + "&" + URLEncoder.encode("pageNo","UTF-8") + "=" + 1;

            Map<String, Object> firstBody = callSafemap(url);

            int totalCount = firstBody == null ? 0 : Integer.parseInt(String.valueOf(firstBody.getOrDefault("totalCount", 0)));

            int totalPages = (int) Math.ceil((double) totalCount / pageSize);
            totalList.addAll(extractItems(firstBody));

            for (int pageNo = 2; pageNo <= totalPages; pageNo++) {
                url = "http://safemap.go.kr/openApiService/data/getConvenienceStoreData.do"
                        + "?" + URLEncoder.encode("serviceKey","UTF-8") + "=" + URLEncoder.encode(safeServiceKey, "UTF-8") // 값도 인코딩!
                        + "&" + URLEncoder.encode("numOfRows","UTF-8") + "=" + pageSize
                        + "&" + URLEncoder.encode("dataType","UTF-8") + "=json"
                        + "&" + URLEncoder.encode("Fclty_Cd","UTF-8") + "=509010"
                        + "&" + URLEncoder.encode("pageNo","UTF-8") + "=" + pageNo;
                Map<String, Object> body = callSafemap(url);
                totalList.addAll(extractItems(body));
                System.out.println("적재된 데이터 수 : "+totalList.size());
            }
            int insertConvenienceStore = safemapService.insertConvenienceStore(totalList);
            System.out.println(insertConvenienceStore);
        } catch (Exception e) {
            log.error("Safemap 수집 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callSafemap(String url) throws Exception {
        var res = Jsoup.connect(url)
                .timeout(60_000)
                .userAgent("Mozilla/5.0")
                .ignoreContentType(true)
                .execute();

        String json = res.body();
        Map<String, Object> root = new ObjectMapper().readValue(json, new TypeReference<>() {});
        Map<String, Object> response = asMap(root.get("response"));
        Map<String, Object> header = asMap(response != null ? response.get("header") : null);

        // resultCode 체크 (정상: "00")
        String resultCode = String.valueOf(header != null ? header.get("resultCode") : null);
        if (!"00".equals(resultCode)) {
            String msg = String.valueOf(header != null ? header.get("resultMsg") : "NO_HEADER");
            // body 없는 상황 방어적으로 빈 구조 리턴
            return Map.of("totalCount", 0, "items", List.of());
        }

        Map<String, Object> body = asMap(response.get("body"));
        if (body == null) {
            return Map.of("totalCount", 0, "items", List.of());
        }
        return body;
    }

    private Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> body) {
        if (body == null) return List.of();
        Object itemsObj = body.get("items");
        if (itemsObj instanceof List) return (List<Map<String, Object>>) itemsObj;

        if (itemsObj instanceof Map) {
            Object item = ((Map<String, Object>) itemsObj).get("item");
            if (item instanceof List) return (List<Map<String, Object>>) item;
            if (item instanceof Map) return List.of((Map<String, Object>) item);
        }
        return List.of();
    }
}
