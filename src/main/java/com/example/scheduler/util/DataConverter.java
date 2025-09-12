package com.example.scheduler.util;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public final class DataConverter {
    private DataConverter() {}

    public static Map<String, Object> xmlToList(StringBuilder urlBuilder) {
        Map<String, Object> resultData = new HashMap<>();
        try {
            System.out.println("Request URL: " + urlBuilder.toString());

            URI uri = new URI(urlBuilder.toString());
            URL url = uri.toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/xml");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                log.error("API 호출 실패. Response Code: {}", responseCode);
                return resultData;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String xmlString = sb.toString();

            // XML 파싱하여 Map 구조로 변환
            resultData = parseXmlToMap(xmlString);

        } catch (Exception e) {
            log.error("XML 데이터 수집 중 오류 발생: {}", e.getMessage(), e);
        }
        return resultData;
    }

    /**
     * XML을 Map으로 변환하는 메서드
     */
    private static Map<String, Object> parseXmlToMap(String xmlString) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xmlString)));

            Element root = document.getDocumentElement();
            return elementToMap(root);

        } catch (Exception e) {
            log.error("XML 파싱 중 오류 발생: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * Element를 Map으로 변환 (재귀 처리)
     */
    private static Map<String, Object> elementToMap(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();

        NodeList children = element.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);

            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                String key = childElement.getTagName();

                // 자식 엘리먼트가 있는지 확인
                if (hasElementChildren(childElement)) {
                    // 중첩 구조 처리
                    Object existingValue = map.get(key);
                    Map<String, Object> childMap = elementToMap(childElement);

                    if (existingValue == null) {
                        map.put(key, childMap);
                    } else if (existingValue instanceof List) {
                        ((List<Object>) existingValue).add(childMap);
                    } else {
                        List<Object> list = new ArrayList<>();
                        list.add(existingValue);
                        list.add(childMap);
                        map.put(key, list);
                    }
                } else {
                    // 텍스트 값 처리
                    String textContent = childElement.getTextContent().trim();
                    if (!textContent.isEmpty()) {
                        Object existingValue = map.get(key);
                        if (existingValue == null) {
                            map.put(key, textContent);
                        } else if (existingValue instanceof List) {
                            ((List<Object>) existingValue).add(textContent);
                        } else {
                            List<Object> list = new ArrayList<>();
                            list.add(existingValue);
                            list.add(textContent);
                            map.put(key, list);
                        }
                    }
                }
            }
        }

        return map;
    }

    /**
     * Element가 자식 Element를 가지고 있는지 확인
     */
    private static boolean hasElementChildren(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }
}
