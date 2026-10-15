package com.example.scheduler.service;

import com.example.scheduler.mapper.ApisDataMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 아파트 단지 좌표 채우기(지오코딩).
 *
 * <p><b>왜 필요한가</b>: 국토교통부 실거래 공개 API 는 좌표를 주지 않는다. 지도에 핀을 찍으려면
 * 주소를 좌표로 바꿔 보관해야 한다. 거래 2,100만 건이 아니라 <b>단지 4.5만 개</b>만 구하면
 * {@code apt_seq} 로 모든 거래에 붙는다.
 *
 * <p><b>두 가지 길을 쓴다</b>
 * <ol>
 *   <li><b>행안부 도로명주소 좌표제공 API</b> — 주소 문자열이 아니라 <b>우리가 가진 코드를 그대로
 *       조립</b>해 보낸다(오타·별칭으로 깨지지 않는다). 보유율 99.7%.
 *       admCd = sgg_cd+umd_cd · rnMgtSn = road_nm_sgg_cd+road_nm_cd · udrtYn = road_nmb_cd ·
 *       buldMnnm = road_nm_bonbun · buldSlno = road_nm_bubun. 응답 좌표는 EPSG:5179 라
 *       {@code resultType=json} 과 함께 좌표계를 WGS84 로 달라고 요청한다.</li>
 *   <li><b>VWorld 지오코더</b> — 1번이 실패했거나 법정동코드가 없는 단지(전월세 전용)를 지번
 *       문자열로 메운다. 지번은 두 표에 100% 있다.</li>
 * </ol>
 *
 * <p><b>백필에서 겪은 함정을 그대로 적용한다</b>(sj-lab/docs/backfill-workflow.md)
 * <ul>
 *   <li>연결·읽기 <b>타임아웃을 반드시</b> 건다 — 없으면 한 건에서 수십 분 멈춘다(실제로 겪음).</li>
 *   <li>한도 초과(429)를 <b>빈 결과로 넘기지 않는다</b> — 조용히 빠진 구간이 생긴다
 *       (광주·전남 실거래가 그렇게 비었다). 한도로 판단되면 그 자리에서 멈추고 알린다.</li>
 *   <li>실패는 표에 상태로 남겨 <b>나중에 다시 시도</b>할 수 있게 한다.</li>
 *   <li>로그·DB 에 <b>키를 남기지 않는다</b>(주소 로그도 키 파라미터는 가린다).</li>
 * </ul>
 */
@Service
public class EstateGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(EstateGeocodeService.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    /** 같은 단지를 끝없이 다시 시도하지 않게 한다(주소가 아예 안 맞는 단지가 있다). */
    private static final int MAX_TRIES = 3;

    /** 행안부 좌표제공 API 응답 좌표계(GRS80 UTM-K). */
    private static final int SRID_JUSO = 5179;
    /** VWorld 는 요청할 때 4326 으로 달라고 한다. */
    private static final int SRID_VWORLD = 4326;

    private static final String JUSO_URL = "https://business.juso.go.kr/addrlink/addrCoordApi.do";
    private static final String VWORLD_URL = "https://api.vworld.kr/req/address";

    private final ApisDataMapper mapper;

    /** 행안부 도로명주소 좌표제공 API 승인키. **환경변수로만** 넣는다(저장소는 public). */
    @Value("${apikey.juso:}")
    private String jusoKey;

    /** VWorld 오픈API 인증키. 없으면 1번만 쓰고 보조 경로는 건너뛴다. */
    @Value("${apikey.vworld:}")
    private String vworldKey;

    /** 한 건 받고 쉬는 시간. 공공 API 가 몰아치는 호출을 싫어한다. */
    @Value("${estate.geocode.delay-ms:120}")
    private long delayMs;

    public EstateGeocodeService(ApisDataMapper mapper) {
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return jusoKey != null && !jusoKey.isBlank();
    }

    /** 거래 표에서 단지를 긁어 단지 마스터에 채운다(좌표는 비운 채). 여러 번 돌려도 안전하다. */
    public Map<String, Object> syncComplexes(String sggCd) {
        mapper.syncAptComplexFromTrade(sggCd);
        mapper.syncAptComplexFromRent(sggCd);
        return mapper.selectComplexGeocodeStats(sggCd);
    }

    public Map<String, Object> stats(String sggCd) {
        return mapper.selectComplexGeocodeStats(sggCd);
    }

    /**
     * 좌표를 받지 못한 단지 {@code limit} 개를 처리한다.
     *
     * @return 처리 결과 요약. {@code quotaExceeded} 가 true 면 **한도에 걸려 중단**된 것이므로
     *         드라이버는 커서를 전진시키지 말고 기다렸다가 다시 불러야 한다.
     */
    public Map<String, Object> geocodeBatch(int limit, String sggCd) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isConfigured()) {
            result.put("configured", false);
            result.put("message", "행안부 좌표제공 API 승인키(apikey.juso)가 없습니다.");
            return result;
        }

        List<Map<String, Object>> targets = mapper.selectPendingComplexes(limit, sggCd, MAX_TRIES);
        int ok = 0;
        int failed = 0;
        int skipped = 0;
        boolean quotaExceeded = false;

        for (Map<String, Object> row : targets) {
            String aptSeq = str(row.get("apt_seq"));
            try {
                // 좌표계가 API 마다 다르다 — 행안부 5179, VWorld 4326. 변환은 PostGIS 가 한다.
                double[] point = geocodeByRoadCode(row);
                String source = "JUSO";
                int srid = SRID_JUSO;
                if (point == null && vworldKey != null && !vworldKey.isBlank()) {
                    point = geocodeByJibun(row);
                    source = "VWORLD";
                    srid = SRID_VWORLD;
                }

                if (point != null) {
                    mapper.updateComplexCoord(aptSeq, point[0], point[1], srid, source, null);
                    ok++;
                } else {
                    mapper.markComplexGeocodeFailed(aptSeq, "FAILED", "좌표를 찾지 못함");
                    failed++;
                }
            } catch (QuotaExceededException e) {
                // 한도다 — 여기서 멈춘다. 이 단지는 건드리지 않아 다음에 다시 시도된다.
                log.warn("지오코딩 한도 초과로 중단합니다(처리 {}건 후)", ok + failed + skipped);
                quotaExceeded = true;
                break;
            } catch (MissingAddressException e) {
                mapper.markComplexGeocodeFailed(aptSeq, "SKIPPED", e.getMessage());
                skipped++;
            } catch (Exception e) {
                mapper.markComplexGeocodeFailed(aptSeq, "FAILED", e.getClass().getSimpleName());
                failed++;
            }
            sleep(delayMs);
        }

        result.put("configured", true);
        result.put("picked", targets.size());
        result.put("ok", ok);
        result.put("failed", failed);
        result.put("skipped", skipped);
        result.put("quotaExceeded", quotaExceeded);
        result.put("stats", mapper.selectComplexGeocodeStats(sggCd));
        return result;
    }

    // ── 1) 행안부 좌표제공 ────────────────────────────────────────────────────
    private double[] geocodeByRoadCode(Map<String, Object> row) throws Exception {
        String admCd = str(row.get("sgg_cd")) + str(row.get("umd_cd"));
        String rnMgtSn = str(row.get("road_nm_sgg_cd")) + str(row.get("road_nm_cd"));
        String buldMnnm = trimZero(str(row.get("road_nm_bonbun")));
        String buldSlno = trimZero(str(row.get("road_nm_bubun")));
        String udrtYn = str(row.get("road_nmb_cd"));
        if (udrtYn.isBlank()) {
            udrtYn = "0";
        }

        if (admCd.length() != 10 || rnMgtSn.length() != 12 || buldMnnm.isBlank()) {
            throw new MissingAddressException("도로명 주소 코드가 모자랍니다");
        }

        String url = JUSO_URL
                + "?confmKey=" + enc(jusoKey)
                + "&admCd=" + enc(admCd)
                + "&rnMgtSn=" + enc(rnMgtSn)
                + "&udrtYn=" + enc(udrtYn)
                + "&buldMnnm=" + enc(buldMnnm)
                + "&buldSlno=" + enc(buldSlno)
                + "&resultType=json";

        String body = call(url);
        // 한도·키 오류는 본문 코드로 온다(HTTP 200 이어도). 조용히 넘기지 않는다.
        if (body.contains("\"errorCode\":\"E0037\"") || body.contains("초과")) {
            throw new QuotaExceededException();
        }
        Double x = pickNumber(body, "entX");
        Double y = pickNumber(body, "entY");
        if (x == null || y == null) {
            return null;
        }
        // 응답은 EPSG:5179 다. 변환은 PostGIS 에 맡기고(매퍼가 srid 를 받는다) 여기서는 숫자만 넘긴다.
        return new double[] {x, y};
    }

    // ── 2) VWorld 지오코더(지번) ─────────────────────────────────────────────
    private double[] geocodeByJibun(Map<String, Object> row) throws Exception {
        String umdNm = str(row.get("umd_nm"));
        String jibun = str(row.get("jibun"));
        if (umdNm.isBlank() || jibun.isBlank()) {
            return null;
        }
        String address = umdNm + " " + jibun;

        String url = VWORLD_URL
                + "?service=address&request=getcoord&version=2.0&crs=epsg:4326&type=PARCEL"
                + "&address=" + enc(address)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("\"status\":\"ERROR\"") && body.contains("QUOTA")) {
            throw new QuotaExceededException();
        }
        Double x = pickNumber(body, "x");
        Double y = pickNumber(body, "y");
        if (x == null || y == null) {
            return null;
        }
        return new double[] {x, y};
    }

    // ── 공통 ────────────────────────────────────────────────────────────────
    private String call(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        try {
            conn.setRequestMethod("GET");
            // 타임아웃을 걸지 않으면 한 건에서 수십 분 멈춘다(백필에서 실제로 겪었다)
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status == 429) {
                throw new QuotaExceededException();
            }
            if (status != 200) {
                throw new IllegalStateException("HTTP " + status);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** 의존성을 늘리지 않으려고 값 하나만 꺼내 쓴다(응답이 단순하다). */
    private Double pickNumber(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int from = at + needle.length();
        while (from < json.length() && (json.charAt(from) == '"' || json.charAt(from) == ' ')) {
            from++;
        }
        int to = from;
        while (to < json.length() && (Character.isDigit(json.charAt(to)) || json.charAt(to) == '.' || json.charAt(to) == '-')) {
            to++;
        }
        if (to == from) {
            return null;
        }
        try {
            return Double.parseDouble(json.substring(from, to));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** "00123" → "123". 행안부 API 는 앞의 0 을 싫어한다. */
    private static String trimZero(String value) {
        String trimmed = value.replaceFirst("^0+", "");
        return trimmed.isEmpty() ? (value.isEmpty() ? "" : "0") : trimmed;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 하루 한도를 넘겼다 — 조용히 넘기지 않고 중단 신호로 쓴다. */
    static class QuotaExceededException extends RuntimeException {}

    /** 주소 조각이 모자라 시도 자체가 불가능하다(다시 시도해도 같다). */
    static class MissingAddressException extends RuntimeException {
        MissingAddressException(String message) {
            super(message);
        }
    }
}
