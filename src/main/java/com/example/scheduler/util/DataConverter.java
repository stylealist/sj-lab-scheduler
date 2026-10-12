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

    /** 연결 대기 상한. 이게 없으면 상대가 응답하지 않을 때 스레드가 영구히 멈춘다. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 응답 읽기 대기 상한. 공공데이터포털이 호출 제한을 걸면 연결만 잡고 데이터를 주지 않는 경우가 있다. */
    private static final int READ_TIMEOUT_MS = 30_000;
    /** 429(호출 과다)·5xx 일 때 다시 시도하는 횟수. */
    private static final int MAX_RETRY = 4;

    /**
     * 재시도까지 모두 실패해 <b>건너뛴</b> 호출 수.
     *
     * <p>백필에서 꼭 필요하다. 호출 한도(429)에 걸려 받지 못한 것과 "그 달에 거래가 없는 것"은
     * 둘 다 "새로 들어간 0건"으로 보여서 구분할 수 없다. 구분하지 못하면 한도에 걸린 구간을
     * "데이터 없음"으로 세어 <b>멈춤 규칙이 잘못 발동</b>하고, 받지도 못한 과거 구간을 건너뛰게 된다.
     * 그래서 건너뛴 횟수를 세어 백필 응답에 함께 돌려준다.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SKIPPED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 한 번의 수집을 시작할 때 0 으로 되돌린다. */
    public static void resetSkippedCount() {
        SKIPPED.set(0);
    }

    /** 이번 수집에서 재시도까지 실패해 건너뛴 호출 수. */
    public static int skippedCount() {
        return SKIPPED.get();
    }

    /**
     * 공공데이터포털 XML 호출.
     *
     * <p><b>2026-10-06 수정 — 백필이 통째로 멈춘 원인 세 가지를 함께 고쳤다.</b>
     * <ol>
     *   <li><b>타임아웃이 없었다.</b> 호출 제한에 걸린 뒤 상대가 응답을 끊으면 스레드가 영구히 대기했다
     *       (실제로 14:54 에 멎어 40분 동안 아무 일도 일어나지 않았다). 이제 연결·읽기 상한을 둔다.</li>
     *   <li><b>429 를 그냥 실패로 넘겼다.</b> 빈 결과를 돌려주면 호출한 쪽은 "그 달에 데이터가 없다"로
     *       받아들여 그 구간을 조용히 건너뛴다. 이제 점점 길게 기다리며 다시 시도한다.</li>
     *   <li><b>serviceKey 가 로그에 그대로 찍혔다.</b> 가려서 남긴다.</li>
     * </ol>
     */
    public static Map<String, Object> xmlToList(StringBuilder urlBuilder) {
        Map<String, Object> resultData = new HashMap<>();
        String requestUrl = urlBuilder.toString();

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                log.debug("API 호출: {}", maskServiceKey(requestUrl));

                URI uri = new URI(requestUrl);
                URL url = uri.toURL();

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/xml");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.connect();

                int responseCode = connection.getResponseCode();

                // 호출 과다(429)·서버 일시 오류(5xx)는 기다렸다 다시 시도한다.
                // 여기서 포기하면 그 달 데이터가 영구히 빠진 채로 넘어간다.
                if (responseCode == 429 || responseCode >= 500) {
                    connection.disconnect();
                    long waitMs = 2000L * attempt * attempt; // 2s → 8s → 18s → 32s
                    log.warn("API 호출 제한/일시 오류 {} — {}ms 뒤 재시도 ({}/{})",
                            responseCode, waitMs, attempt, MAX_RETRY);
                    Thread.sleep(waitMs);
                    continue;
                }

                if (responseCode != 200) {
                    log.error("API 호출 실패. Response Code: {} / {}", responseCode, maskServiceKey(requestUrl));
                    return resultData;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                // XML 파싱하여 Map 구조로 변환
                return parseXmlToMap(sb.toString());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return resultData;
            } catch (Exception e) {
                // 타임아웃도 여기로 온다. 마지막 시도가 아니면 다시 시도한다.
                if (attempt < MAX_RETRY) {
                    log.warn("API 호출 오류({}) — 재시도 ({}/{})", e.getClass().getSimpleName(), attempt, MAX_RETRY);
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return resultData;
                    }
                    continue;
                }
                log.error("XML 데이터 수집 중 오류 발생: {} / {}", e.getMessage(), maskServiceKey(requestUrl));
                return resultData;
            }
        }

        SKIPPED.incrementAndGet();
        log.error("재시도 {}번이 모두 실패했습니다. 이 요청은 건너뜁니다: {}", MAX_RETRY, maskServiceKey(requestUrl));
        return resultData;
    }

    /** 로그에 남길 주소에서 serviceKey 값을 가린다. 이 저장소는 public 이고 로그도 수집된다. */
    private static String maskServiceKey(String url) {
        return url == null ? "" : url.replaceAll("(?i)(serviceKey=)[^&]+", "$1***");
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
