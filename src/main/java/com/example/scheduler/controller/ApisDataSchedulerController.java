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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
public class ApisDataSchedulerController {
    @Value("${apikey.apis.busInfo}")
    private String apisBusInfoServiceKey;
    private static ApisDataService apisDataService;
    public ApisDataSchedulerController(ApisDataService apisDataService) {
        this.apisDataService = apisDataService;
    }
    @Scheduled(cron = "0 24 14 * * *")
    @RequestMapping("/apis/bus/cityInfo")
    public void busCityInfo() {
        try {
            StringBuilder urlBuilder = new StringBuilder("http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getCtyCodeList");
            urlBuilder.append("?serviceKey=").append(apisBusInfoServiceKey);
            urlBuilder.append("&_type=json");

            List<Map<String, Object>> param = apisDataToList(urlBuilder);

            int insertApisBusCityInfo = apisDataService.insertApisBusCityInfo(param);
            System.out.println(param);
        } catch (Exception e) {
            log.error("busInfo 수집 실패", e);
        }
    }

    private static List<Map<String, Object>> apisDataToList(StringBuilder urlBuilder){
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
            log.info("apisDataToList 실패");
        }
        return resultData;
    }
}
