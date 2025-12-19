package com.example.scheduler.controller;

import com.example.scheduler.dto.BusCityInfoDto;
import com.example.scheduler.service.ApisDataService;
import com.example.scheduler.service.ItsDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
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
@Transactional
public class ItsDataSchedulerController {
    @Value("${apikey.its}")
    private String itsServiceKey;
    private static ItsDataService itsDataService;
    public ItsDataSchedulerController(ItsDataService itsDataService) {
        this.itsDataService = itsDataService;
    }

    /**
     * 국가교통정보센터 실시간 CCTV API 데이터를 DB에 저장하는 기능
     * 대상 레이어 : (CCTV)
     * 업데이트 시간 : 매일 06시 00분
     */
    @Scheduled(cron = "0 00 06 * * *")
    @RequestMapping("/its/cctv-info")
    public void cctvInfo() {
        log.info("/schedule/cctv");
        List<Map<String, Object>> cctvDataList = new ArrayList<>();

        try {
            ArrayList<String> typeList = new ArrayList<>();

            typeList.add("ex"); // 고속도로
            typeList.add("its"); // 국도

            for(String type : typeList){
                StringBuilder sb = new StringBuilder();
                StringBuilder urlBuilder = new StringBuilder("https://openapi.its.go.kr:9443/cctvInfo"); /*URL*/
                urlBuilder.append("?" + URLEncoder.encode("apiKey", "UTF-8") + "=" + URLEncoder.encode(itsServiceKey, "UTF-8")); /*공개키*/
                urlBuilder.append("&" + URLEncoder.encode("type","UTF-8") + "=" + URLEncoder.encode(type, "UTF-8")); /*도로유형*/
                urlBuilder.append("&" + URLEncoder.encode("cctvType","UTF-8") + "=" + URLEncoder.encode("1", "UTF-8")); /*CCTV유형*/
                urlBuilder.append("&" + URLEncoder.encode("minX","UTF-8") + "=" + URLEncoder.encode("126.1485222", "UTF-8")); /*최소경도영역*/
                urlBuilder.append("&" + URLEncoder.encode("maxX","UTF-8") + "=" + URLEncoder.encode("129.547959", "UTF-8")); /*최대경도영역*/
                urlBuilder.append("&" + URLEncoder.encode("minY","UTF-8") + "=" + URLEncoder.encode("34.330779", "UTF-8")); /*최소위도영역*/
                urlBuilder.append("&" + URLEncoder.encode("maxY","UTF-8") + "=" + URLEncoder.encode("38.5", "UTF-8")); /*최대위도영역*/
                urlBuilder.append("&" + URLEncoder.encode("getType","UTF-8") + "=" + URLEncoder.encode("json", "UTF-8")); /*출력타입*/

                cctvDataList.addAll(apiCctvDataToList(urlBuilder));

            }
            int insertItsCctvInfo = itsDataService.insertItsCctvInfo(cctvDataList);
            System.out.println("insertItsCctvInfo : " + insertItsCctvInfo);
        }catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static List<Map<String, Object>> apiCctvDataToList(StringBuilder urlBuilder){
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

            resultData = (List<Map<String, Object>>) apisData.get("data");
        } catch (Exception e){
            e.printStackTrace();
        }
        return resultData;
    }
}
