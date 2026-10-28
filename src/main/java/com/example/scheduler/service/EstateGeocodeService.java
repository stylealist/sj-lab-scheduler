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
    // 건물 폴리곤은 한 단지가 수십 동이라 응답이 크다 — 10초로는 끊긴다(실제로 끊겼다).
    private static final int READ_TIMEOUT_MS = 20_000;
    /** 같은 단지를 끝없이 다시 시도하지 않게 한다(주소가 아예 안 맞는 단지가 있다). */
    private static final int MAX_TRIES = 6;
    /** 속도 제한(E0007)에 걸렸을 때 쉬는 시간. 이만큼 쉬어도 안 풀리면 하루 한도로 본다. */
    private static final long[] THROTTLE_BACKOFF_MS = {2_000, 8_000, 18_000, 32_000};

    /** 행안부 좌표제공 API 응답 좌표계(GRS80 UTM-K). */
    private static final int SRID_JUSO = 5179;
    /** VWorld 는 요청할 때 4326 으로 달라고 한다. */
    private static final int SRID_VWORLD = 4326;

    private static final String JUSO_URL = "https://business.juso.go.kr/addrlink/addrCoordApi.do";
    private static final String VWORLD_URL = "https://api.vworld.kr/req/address";
    /** 브이월드 데이터 API(건물·연속지적도). 단지 테두리의 원천이다. */
    private static final String VWORLD_DATA_URL = "https://api.vworld.kr/req/data";
    /** 건물 레이어. 아파트 동 하나하나가 폴리곤으로 들어 있다. */
    private static final String LAYER_BUILDING = "LT_C_SPBD";
    /** 연속지적도(필지). **단지 대지 영역의 원천**이고, 건물이 안 잡힐 때의 대비책도 된다. */
    private static final String LAYER_PARCEL = "LP_PA_CBND_BUBUN";

    /** 영역을 대지(필지)로 받는 모드. 기본값이다. */
    private static final String MODE_SITE = "site";

    /**
     * 영역 수집 규칙의 버전. 출처 뒤에 붙여 저장한다(예: {@code VWORLD_LOT_V2}).
     *
     * <p><b>규칙을 고치면 이 값을 올릴 것.</b> 그러면 앞 버전으로 받아 둔 단지가 자동으로 다시
     * 대상이 된다 — 표를 직접 고쳐 표시를 지우는 일(운영 DML)을 하지 않으려는 장치다.
     * V2 = 필지 선택(겹침 10% 이상) + 끊긴 조각 잇기(15m 닫기).
     * V3 = 건물을 <b>도로명 주소로</b> 모아 단지 전체를 씨앗으로 쓴다.
     * V4 = 거기에 **필지가 그 단지 건물을 90% 이상 덮는지**로 지적을 믿을지 판단한다
     * (엄격 → 느슨 → 그래도 안 되면 건물 윤곽). 재건축 단지가 옛 아파트 모양으로 나오던 문제.
     * V5 = <b>핀을 영역 가운데로</b> 옮기고, 지적을 못 믿는 단지도 건물이 올라선 필지까지 덮는다.
     * V6 = <b>1·2단지로 나뉜 단지를 합친다</b>(가까운 동 묶음 중 살아 있는 다른 단지 핀이 없는 것).
     * V7 = 건물 기준 영역을 <b>오목 외곽선(ConcaveHull)</b> 으로 만들고 <b>안쪽 구멍을 메운다</b>
     * — 동 사이가 뚫리고 떨어진 동이 빠지던 문제.
     */
    private static final String SITE_VER = "_V11";
    /** 영역을 건물 외곽으로 받는 종전 모드(비교·대비용). */
    private static final String MODE_BUILDING = "bldg";

    /** 필지를 받을 범위를 건물 외곽보다 조금 넓힌다(약 40m). 단지 경계에 걸친 필지까지 담는다. */
    private static final double LON_PAD = 0.00045;
    private static final double LAT_PAD = 0.00036;

    /** 받아 온 필지 중 DB 로 보낼 것을 고르는 여유(약 5m). 건물에 닿을 수 있는 것만 남긴다. */
    private static final double NEAR_PAD = 0.00006;

    /** 같은 번지라도 핀에서 이보다 먼 동은 그 단지로 보지 않는다(신도시 한 번지가 수십만㎡ 다). */
    private static final double BLDG_MAX_DIST_M = 350;

    /** 그 단지 건물을 찾을 범위(핀 기준 약 450m). 큰 단지도 한 번에 담긴다. */
    private static final double BLDG_LON_PAD = 0.0051;
    private static final double BLDG_LAT_PAD = 0.0040;

    /** "101동"처럼 숫자로 시작하는 동 이름 — 아파트 동을 가려내는 표시다. */
    private static final java.util.regex.Pattern DONG_NAME =
            java.util.regex.Pattern.compile("^[0-9]+동$");

    /** 방금 받은 핀 주변 건물(같은 호출 안에서 이웃 블록 후보를 고르는 데 쓴다). */
    private List<Map<String, String>> lastBuildings = List.of();

    /** 같은 번지에 있지만 그 단지 것이 아닌 동들(도로명 주소가 다른 동). 필지 주인을 가릴 때 쓴다. */
    private List<String> lastRivals = List.of();

    /** GeoJSON 좌표쌍 {@code [경도,위도]} — 한국 범위만 좌표로 본다(높이·식별자를 걸러낸다). */
    private static final java.util.regex.Pattern COORD_PAIR = java.util.regex.Pattern.compile(
            "\\[\\s*(12[4-9]\\.\\d+|13[0-2]\\.\\d+)\\s*,\\s*(3[0-9]\\.\\d+)\\s*[\\],]");

    private final ApisDataMapper mapper;

    /** 행안부 도로명주소 좌표제공 API 승인키. **환경변수로만** 넣는다(저장소는 public). */
    @Value("${apikey.juso:}")
    private String jusoKey;

    /** VWorld 오픈API 인증키. 없으면 1번만 쓰고 보조 경로는 건너뛴다. */
    @Value("${apikey.vworld:}")
    private String vworldKey;

    /**
     * 브이월드 데이터 API 가 요구하는 "사용 도메인". **키를 발급할 때 적은 주소와 같아야 한다** —
     * 로컬과 운영이 서로 다른 키·도메인이라 값으로 받는다.
     */
    @Value("${apikey.vworld-domain:http://localhost:4200}")
    private String vworldDomain;

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
        Map<String, Object> stats = new LinkedHashMap<>(mapper.selectComplexGeocodeStats(sggCd));
        // 테두리 컬럼은 나중에 더해진 것이다 — 아직 없는 DB 에서도 좌표 진행은 보여야 한다.
        try {
            stats.put("with_boundary", mapper.selectComplexBoundaryCount(sggCd));
        } catch (Exception e) {
            stats.put("with_boundary", null);
        }
        return stats;
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
        result.put("stats", stats(sggCd));
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

        String body = callJuso(url);
        Double x = pickNumber(body, "entX");
        Double y = pickNumber(body, "entY");
        if (x == null || y == null) {
            return null;
        }
        // 응답은 EPSG:5179 다. 변환은 PostGIS 에 맡기고(매퍼가 srid 를 받는다) 여기서는 숫자만 넘긴다.
        return new double[] {x, y};
    }

    // ── 단지 테두리(연속지적도 필지) ──────────────────────────────────────────

    /**
     * 테두리를 못 받은 단지 {@code limit} 개에 필지 폴리곤을 채운다.
     *
     * <p><b>왜 하는가</b>: 행안부 좌표는 단지 중심이 아니라 <b>건물 출입구</b>라 핀이 가장자리에
     * 찍힌다(은마아파트에서 실제로 확인). 필지 폴리곤을 받아 두면 ① 핀을 그 안쪽 중심으로 옮기고
     * ② 호갱노노처럼 단지 테두리를 그릴 수 있다 — <b>한 번에 두 가지가 풀린다.</b>
     *
     * <p><b>두 번 묻는다</b>: 지번 → (브이월드 지오코더) → <b>PNU</b> → (브이월드 연속지적도) → 폴리곤.
     * PNU 는 필지 고유번호 19자리이고 지오코더 응답의 {@code level4LC} 에 들어 있다.
     */
    /** 받아 둔 테두리를 지운다(기준이 바뀌어 다시 받을 때). 좌표는 그대로 둔다. */
    public int resetBoundaries(String sggCd) {
        return mapper.resetComplexBoundary(sggCd);
    }

    public Map<String, Object> boundaryBatch(int limit, String sggCd) {
        return boundaryBatch(limit, sggCd, MODE_SITE);
    }

    /**
     * 단지 영역을 받는다.
     *
     * @param mode {@code "site"}(기본) 면 <b>대지(지적도 필지)</b>를, {@code "bldg"} 면 종전처럼
     *             <b>건물 외곽</b>을 테두리로 쓴다.
     */
    public Map<String, Object> boundaryBatch(int limit, String sggCd, String mode) {
        return boundaryBatch(limit, sggCd, mode, null);
    }

    /** {@code aptSeq} 를 주면 **그 단지만** 다시 받는다(규칙을 고친 뒤 눈으로 확인하는 용도). */
    public Map<String, Object> boundaryBatch(int limit, String sggCd, String mode, String onlyAptSeq) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (vworldKey == null || vworldKey.isBlank()) {
            result.put("configured", false);
            result.put("message", "VWorld 인증키(apikey.vworld)가 없습니다.");
            return result;
        }

        boolean siteMode = !MODE_BUILDING.equals(mode);
        List<Map<String, Object>> targets = mapper.selectComplexesForBoundary(
                limit, sggCd, siteMode ? MODE_SITE : MODE_BUILDING, SITE_VER, onlyAptSeq);
        int ok = 0;
        int missed = 0;
        int siteOk = 0;
        boolean quotaExceeded = false;

        for (Map<String, Object> row : targets) {
            String aptSeq = str(row.get("apt_seq"));
            try {
                // ① 정확한 필지 키(PNU)를 정한다.
                //    **이미 받아 둔 PNU 가 있으면 그것을 쓴다.** 같은 값을 다시 물으면 행안부
                //    속도 제한에 걸려 10분씩 쉬게 된다 — 드라이버를 여러 개 띄우면 거의 매번
                //    걸린다(2026-10-21 실측 제한 1,775회). PNU 는 단지가 재건축되지 않는 한
                //    바뀌지 않으므로 다시 물을 이유가 없다.
                //
                //    없을 때만 물어본다. **실거래의 지번을 믿지 않는다** — 재건축으로 지번이 바뀐
                //    단지가 있어 옛 지번으로 받으면 엉뚱한 땅이 그려진다(래미안안양메가트리아).
                //    행안부가 좌표와 함께 주는 건물관리번호 앞 19자리가 그 건물의 진짜 PNU 다.
                String pnu = str(row.get("pnu"));
                if (pnu.length() != 19) {
                    pnu = pnuFromRoadAddress(row);
                }
                if (pnu.isBlank()) {
                    pnu = lookupPnu(row);   // 도로명 코드가 없는 단지(전월세 전용)는 지번으로
                }
                if (pnu.isBlank()) {
                    log.warn("테두리 실패 {} : PNU 를 못 받음", aptSeq);
                    // **시도했다는 표시를 남긴다** — 안 남기면 이 단지가 큐 맨 앞에 영구히 남아
                    // 매 회차 같은 단지만 되풀이한다(2026-10-21 실제로 50개가 반복됐다).
                    if (siteMode) {
                        mapper.markComplexBoundarySource(aptSeq, "NO_PNU" + SITE_VER);
                    }
                    missed++;
                    continue;
                }

                // ② 그 단지의 **건물들**을 받는다(동 단위 폴리곤).
                //    핀 주변 건물을 한 번에 받아 **도로명 주소가 같은 것**과 **그 지번에 선 것**을
                //    모두 모은다 — 지번만으로 찾으면 여러 지번에 걸친 단지가 일부만 잡히고
                //    (파크리오 29동 중 5동), 도로명만으로 찾으면 주소가 빈 단지를 놓친다.
                List<String> parts = siteMode ? fetchComplexBuildings(row, pnu) : List.of();
                if (parts.isEmpty()) {
                    // **이 폴백에도 거리 상한을 쓴다**(2026-10-28, _V11). pnu 의 본번이 0000 인 단지
                    // (지번을 못 받은 신축)는 like 검색이 그 법정동 건물을 폭넓게 물어와, 신도시에서는
                    // 지구 전체가 한 단지가 된다(평택 화양지구 두 단지가 각각 718,657㎡ 였다).
                    parts = nearPin(fetchBuildingGeoJsons(pnu),
                            asDouble(row.get("lon")), asDouble(row.get("lat")));
                }

                // ③ 대지 모드: 건물을 씨앗으로 **그 건물이 올라 있는 필지들**을 합쳐 영역으로 쓴다.
                //    건물만 쓰면 그 지번에 선 동들만 그려져, 여러 지번에 걸친 단지는 영역이
                //    실제의 몇 분의 일이 된다(실측 파크리오 1만㎡ / 실제 23만㎡대).
                //    필지를 못 받거나 고를 것이 없으면 아래 건물 경로로 내려간다.
                if (siteMode) {
                    if (!parts.isEmpty()) {
                        // 같은 번지에 있는 **남의 동**. 필지마다 어느 건물이 가까운지로 주인을
                        // 가리는 데 쓴다(신도시는 한 번지에 단지가 여럿이다).
                        List<String> rivals = lastRivals;
                        // 필지는 **씨앗 건물 주변**만 받는다 — 이웃 블록까지 범위를 넓히면 한 번에
                        // 받는 300개 상한에 걸려 정작 단지 밑 필지를 놓친다(2026-10-22 실측).
                        List<Map<String, String>> parcels = fetchParcelsAround(parts);
                        if (!parcels.isEmpty()
                                && mapper.updateComplexSite(aptSeq, pnu, parcels, parts, rivals,
                                        "VWORLD_LOT" + SITE_VER, "BLDG_SITE" + SITE_VER) > 0) {
                            ok++;
                            siteOk++;
                            sleep(delayMs);
                            continue;
                        }
                        log.info("대지 필지를 못 골라 건물 외곽으로 둔다 {} (pnu={})", aptSeq, pnu);
                    } else {
                        // 건물이 안 잡히는 단지(철거·미등록)는 **그 필지 자체**를 대지로 쓴다.
                        // 종전에는 VWORLD_CBND 로 저장했는데, 그 표시는 "시도 끝남"에 들지 않아
                        // 다음 회차에 또 뽑혔다(2026-10-21 동작구에서 11건이 그랬다).
                        String parcel = fetchParcelGeoJson(pnu);
                        if (parcel != null) {
                            mapper.updateComplexBoundary(aptSeq, pnu, List.of(parcel), "VWORLD_LOT" + SITE_VER);
                            ok++;
                            siteOk++;
                            sleep(delayMs);
                            continue;
                        }
                        log.warn("테두리 실패 {} : 건물·필지 모두 없음 (pnu={})", aptSeq, pnu);
                        mapper.markComplexBoundarySource(aptSeq, "NO_GEOM" + SITE_VER);
                        missed++;
                        sleep(delayMs);
                        continue;
                    }
                }

                // 대지로 못 받았으면 건물 외곽으로 두되 **그렇게 끝났다고 표시**한다
                // (VWORLD_BLDG_ONLY). 종전 표시(VWORLD_BLDG)로 두면 다음 회차에 또 뽑힌다.
                String source = siteMode ? "BLDG_ONLY" + SITE_VER : "VWORLD_BLDG";
                if (parts.isEmpty()) {
                    // 건물이 안 잡히면 필지로라도 그린다(없는 것보다 낫다).
                    String parcel = fetchParcelGeoJson(pnu);
                    if (parcel == null) {
                        log.warn("테두리 실패 {} : 건물·필지 모두 없음 (pnu={})", aptSeq, pnu);
                        if (siteMode) {
                            mapper.markComplexBoundarySource(aptSeq, "NO_GEOM" + SITE_VER);
                        }
                        missed++;
                        continue;
                    }
                    parts = List.of(parcel);
                    source = "VWORLD_CBND";
                }

                mapper.updateComplexBoundary(aptSeq, pnu, parts, source);
                ok++;
            } catch (QuotaExceededException e) {
                log.warn("테두리 수집 한도 초과로 중단합니다(처리 {}건 후)", ok + missed);
                quotaExceeded = true;
                break;
            } catch (Exception e) {
                // 테두리는 없어도 화면이 동작한다 — 실패를 상태로 남기지 않고 다음에 다시 시도한다.
                // 다만 **왜 못 받았는지는 남긴다** — 조용히 삼키면 0건인 이유를 찾을 수 없다.
                log.warn("테두리 실패 {} : {} {}", aptSeq, e.getClass().getSimpleName(), e.getMessage());
                missed++;
            }
            sleep(delayMs);
        }

        result.put("configured", true);
        result.put("mode", siteMode ? MODE_SITE : MODE_BUILDING);
        result.put("picked", targets.size());
        result.put("ok", ok);
        // 그중 대지(필지)로 받은 수 — 나머지는 건물 외곽으로 떨어진 것이다
        result.put("site", siteOk);
        result.put("missed", missed);
        result.put("quotaExceeded", quotaExceeded);
        result.put("stats", stats(sggCd));
        return result;
    }

    /**
     * 그 단지의 건물들을 모은다 — <b>핀 주변을 한 번에 받아 주소로 고른다.</b>
     *
     * <p>지번(PNU)으로만 찾으면 여러 지번에 걸친 단지가 일부만 잡힌다(실측: 파크리오 29동 중 5동,
     * 바닥 11,253㎡ → 2,640㎡). 건물 레이어에는 <b>도로명과 건물번호</b>가 있고 그것이 "이 단지"를
     * 가리키므로, 둘(도로명 일치 · 그 지번에 선 건물)을 합쳐 쓴다.
     *
     * <p>건물 레이어는 <b>최신</b>이다(2024년 준공 평촌엘프라우드의 201동이 들어 있다) — 그래서
     * 재건축 단지도 지금 서 있는 건물로 영역을 만들 수 있다.
     */
    private List<String> fetchComplexBuildings(Map<String, Object> row, String pnu) throws Exception {
        double lon = asDouble(row.get("lon"));
        double lat = asDouble(row.get("lat"));
        if (lon == 0 || lat == 0) {
            return List.of();
        }
        // 핀 주변 약 450m — 헬리오시티(84동)처럼 넓은 단지도 한 번에 담기는 크기다
        String geomFilter = String.format(
                java.util.Locale.ROOT, "BOX(%.7f,%.7f,%.7f,%.7f)",
                lon - BLDG_LON_PAD, lat - BLDG_LAT_PAD, lon + BLDG_LON_PAD, lat + BLDG_LAT_PAD);

        String url = VWORLD_DATA_URL
                + "?service=data&request=GetFeature&data=" + LAYER_BUILDING + "&format=json&size=500"
                + "&crs=EPSG:4326&geometry=true&attribute=true"
                + "&geomFilter=" + enc(geomFilter)
                + "&domain=" + enc(vworldDomain)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("QUOTA") || body.contains("OVER_LIMIT")) {
            throw new QuotaExceededException();
        }

        String roadNm = str(row.get("road_nm"));
        String buldNo = buildingNumberOf(row);
        lastBuildings = pickBuildings(body);

        // **같은 번지(본번)의 건물이 그 단지다.** PNU 19자리는 법정동(10) + 산여부(1) + 본번(4) +
        // 부번(4) 이라, 앞 15자리가 같으면 "같은 번지의 1·2·3…" 이다(1393, 1393-1, 1393-2 …).
        // 번지가 다른 옆 단지는 애초에 섞이지 않는다 — 주소나 거리로 고르면 섞인다
        // (2026-10-26: 거리로 이어 붙이다가 거의 모든 단지가 옆 단지 건물을 머금었다).
        //
        // 다만 신도시는 한 번지가 아주 커서 **한 번지에 단지가 여럿**이다(전국 살아 있는 단지의
        // 31%). 그래서 번지 안에서 다시 **도로명 주소(건물번호)** 로 우리 동을 가리고, 같은
        // 번지의 남의 동은 rivals 로 따로 모아 둔다 — 필지의 주인은 PostGIS 가 "어느 건물이
        // 가까운가"로 정한다(2026-10-27).
        String lotKey = pnu.length() >= 15 ? pnu.substring(0, 15) : "";
        List<Map<String, String>> inLot = new java.util.ArrayList<>();
        for (Map<String, String> one : lastBuildings) {
            if (lotKey.isBlank() || !one.getOrDefault("bd_mgt_sn", "").startsWith(lotKey)) {
                continue;
            }
            // **핀에서 너무 먼 동은 같은 번지라도 그 단지가 아니다**(2026-10-28, _V11).
            // 신도시 한 번지는 수십만㎡ 라 번지 안에 블록이 여러 개 들어 있다. 주소로 우리 동을
            // 가리지 못해 거리로 고를 때, 상한이 없으면 옆 블록 동까지 끌어온다
            // (평택 e편한세상 하이센트가 718,657㎡ 였다). 대단지도 핀에서 가장 먼 동이 300m 안이다.
            double[] center = centerOf(one.get("geom"));
            if (center != null && distMeters(center[0], center[1], lon, lat) > BLDG_MAX_DIST_M) {
                continue;
            }
            inLot.add(one);
        }

        // **같은 번지의 다른 단지 주소를 함께 받아 둔다**(rival_addrs). 우리 주소로 우리 동을
        // 가리는 것만으로는 부족하다 — 건물 레이어의 도로명이 단지 표와 어긋나면(구축·소형에서
        // 흔하다) "맞는 동이 없음"이 되어 **번지 전체를 삼켰다**(2026-10-27: 부산 장천로 한 번지의
        // 6개 단지가 모두 65만㎡, 구로 승윤노블리안이 51만㎡). 그래서 남의 주소에 맞는 동은
        // 덜어내고, 어느 주소에도 안 맞는 동은 **핀이 가까운 쪽**으로 보낸다.
        List<RivalAddr> rivalAddrs = parseRivalAddrs(str(row.get("rival_addrs")));

        List<String> mine = new java.util.ArrayList<>();
        lastRivals = new java.util.ArrayList<>();
        List<Map<String, String>> unclear = new java.util.ArrayList<>();
        boolean hasAddr = !roadNm.isBlank() && !buldNo.isBlank();
        for (Map<String, String> one : inLot) {
            String rd = orEmpty(one.get("rd_nm"));
            String no = orEmpty(one.get("buld_no"));
            if (hasAddr && roadNm.equals(rd) && buldNo.equals(no)) {
                mine.add(one.get("geom"));
                continue;
            }
            boolean theirs = false;
            for (RivalAddr addr : rivalAddrs) {
                if (!addr.roadNm.isBlank() && !addr.buldNo.isBlank()
                        && addr.roadNm.equals(rd) && addr.buldNo.equals(no)) {
                    theirs = true;
                    break;
                }
            }
            if (theirs) {
                lastRivals.add(one.get("geom"));
            } else {
                unclear.add(one);
            }
        }

        if (rivalAddrs.isEmpty()) {
            // 번지를 혼자 쓰는 단지 — 번지 안 모든 동이 그 단지다.
            for (Map<String, String> one : unclear) {
                mine.add(one.get("geom"));
            }
        } else if (!mine.isEmpty()) {
            // **우리 동을 주소로 찾았으면 거기서 멈춘다.** 주소를 못 가린 동까지 거리로 끌어오면
            // 멀쩡했던 단지가 번지를 삼킨다(2026-10-27: 구로 주공2 가 2.8만 → 30만㎡ 가 됐다).
            // 모호한 동은 남의 동 쪽에 세워 두는 편이 안전하다 — 필지가 덜 잡히는 쪽이 낫다.
            for (Map<String, String> one : unclear) {
                lastRivals.add(one.get("geom"));
            }
        } else {
            // 우리 주소로 한 동도 못 찾은 경우에만 **핀이 가까운 동**을 그 단지로 본다.
            for (Map<String, String> one : unclear) {
                double[] center = centerOf(one.get("geom"));
                if (center == null) {
                    continue;   // 좌표를 못 읽은 동은 어느 쪽에도 넣지 않는다
                }
                double toMine = distMeters(center[0], center[1], lon, lat);
                double toRival = Double.MAX_VALUE;
                for (RivalAddr addr : rivalAddrs) {
                    if (addr.lon == 0 || addr.lat == 0) {
                        continue;
                    }
                    toRival = Math.min(toRival, distMeters(center[0], center[1], addr.lon, addr.lat));
                }
                if (toMine <= toRival) {
                    mine.add(one.get("geom"));
                } else {
                    lastRivals.add(one.get("geom"));
                }
            }
        }
        if (!mine.isEmpty()) {
            return mine;
        }

        // 번지로 한 동도 못 찾은 경우에만 번지를 벗어나 도로명 주소로 찾는다(지번이 깨진 단지 대비).
        // **여기에도 거리 상한이 필요하다**(2026-10-28, _V11). pnu 의 본번이 0000 인 단지
        // (지번을 못 받은 신축)는 늘 이 경로를 타는데, 신도시는 같은 도로명이 2km 넘게 이어져
        // 지구 전체를 모았다(평택 화양지구 e편한세상 두 단지가 각각 718,657㎡).
        lastRivals = List.of();
        for (Map<String, String> one : lastBuildings) {
            boolean sameRoad = !roadNm.isBlank() && !buldNo.isBlank()
                    && roadNm.equals(one.get("rd_nm")) && buldNo.equals(one.get("buld_no"));
            if (!sameRoad) {
                continue;
            }
            double[] center = centerOf(one.get("geom"));
            if (center != null && distMeters(center[0], center[1], lon, lat) > BLDG_MAX_DIST_M) {
                continue;
            }
            mine.add(one.get("geom"));
        }
        return mine;
    }

    /** 도로명 건물번호 "본번" 또는 "본번-부번"(건물 레이어의 buld_no 와 같은 형식). */
    private static String buildingNumberOf(Map<String, Object> row) {
        return buildingNumberOf(str(row.get("road_nm_bonbun")), str(row.get("road_nm_bubun")));
    }

    private static String buildingNumberOf(String rawBonbun, String rawBubun) {
        String bonbun = trimZero(rawBonbun);
        if (bonbun.isBlank()) {
            return "";
        }
        String bubun = trimZero(rawBubun);
        return bubun.isBlank() || "0".equals(bubun) ? bonbun : bonbun + "-" + bubun;
    }

    /** 같은 번지를 함께 쓰는 다른 단지의 주소와 핀. */
    private static final class RivalAddr {
        private final String roadNm;
        private final String buldNo;
        private final double lon;
        private final double lat;

        private RivalAddr(String roadNm, String buldNo, double lon, double lat) {
            this.roadNm = roadNm;
            this.buldNo = buldNo;
            this.lon = lon;
            this.lat = lat;
        }
    }

    /**
     * {@code 도로명|본번|부번|경도|위도} 를 {@code ;} 로 이어 붙인 문자열을 읽는다.
     *
     * <p>JSON 이 아니라 구분자 문자열로 받는다 — 도로명·건물번호에 {@code |}·{@code ;} 가
     * 들어갈 일이 없어 파서를 들일 이유가 없다.
     */
    private static List<RivalAddr> parseRivalAddrs(String raw) {
        List<RivalAddr> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split(";")) {
            String[] field = chunk.split("\\|", -1);
            if (field.length < 5) {
                continue;
            }
            out.add(new RivalAddr(
                    field[0].trim(), buildingNumberOf(field[1], field[2]),
                    toDouble(field[3]), toDouble(field[4])));
        }
        return out;
    }

    private static double toDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 건물 폴리곤의 가운데(둘러싼 사각형의 중심). 어느 핀에 가까운지 보는 데만 쓴다. */
    private static double[] centerOf(String geoJson) {
        double[] box = geoJson == null ? null : extentOf(List.of(geoJson));
        return box == null ? null : new double[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2};
    }

    /**
     * 핀에서 {@link #BLDG_MAX_DIST_M} 안의 동만 남긴다. 핀이 없으면 손대지 않는다.
     *
     * <p>그 안에 한 동도 없으면 **핀에서 가장 가까운 동과 그 동 주변 150m** 를 쓴다. 핀이 단지에서
     * 멀리 찍힌 단지(두 단지가 같은 좌표를 받은 평택 화양지구처럼)도 자기 동은 찾되, 지구 전체를
     * 모으지는 않게 하는 마지막 그물이다(2026-10-28, _V11).
     */
    private static List<String> nearPin(List<String> geoJsons, double lon, double lat) {
        if (lon == 0 || lat == 0) {
            return geoJsons;
        }
        List<String> out = new java.util.ArrayList<>();
        String nearest = null;
        double[] nearestCenter = null;
        double nearestDist = Double.MAX_VALUE;
        for (String one : geoJsons) {
            double[] center = centerOf(one);
            if (center == null) {
                out.add(one);
                continue;
            }
            double dist = distMeters(center[0], center[1], lon, lat);
            if (dist <= BLDG_MAX_DIST_M) {
                out.add(one);
            }
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = one;
                nearestCenter = center;
            }
        }
        if (!out.isEmpty() || nearest == null) {
            return out;
        }
        out.add(nearest);
        for (String one : geoJsons) {
            if (one.equals(nearest)) {
                continue;
            }
            double[] center = centerOf(one);
            if (center != null
                    && distMeters(center[0], center[1], nearestCenter[0], nearestCenter[1]) <= 150) {
                out.add(one);
            }
        }
        return out;
    }

    /** 경위도 두 점 사이 거리(m). 한 번지 안을 비교하는 용도라 평면 근사로 충분하다. */
    private static double distMeters(double lon1, double lat1, double lon2, double lat2) {
        double dx = (lon1 - lon2) * 88_000;
        double dy = (lat1 - lat2) * 111_000;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 건물 응답에서 {@code geometry} 와 주소 속성(rd_nm·buld_no·bd_mgt_sn)을 함께 꺼낸다. */
    private static List<Map<String, String>> pickBuildings(String json) {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int at = json.indexOf("\"geometry\"", from);
            if (at < 0) {
                break;
            }
            String geom = pickGeometry(json.substring(at));
            if (geom == null) {
                break;
            }
            int next = json.indexOf("\"geometry\"", at + 10);
            String chunk = next < 0 ? json.substring(at) : json.substring(at, next);
            Map<String, String> one = new LinkedHashMap<>();
            one.put("geom", geom);
            one.put("rd_nm", orEmpty(pickText(chunk, "rd_nm")));
            one.put("buld_no", orEmpty(pickText(chunk, "buld_no")));
            one.put("bd_mgt_sn", orEmpty(pickText(chunk, "bd_mgt_sn")));
            // 동 이름("101동") — 아파트 동인지 가려내는 데 쓴다
            one.put("buld_nm_dc", orEmpty(pickText(chunk, "buld_nm_dc")));
            list.add(one);
            from = at + 10;
        }
        return list;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    /**
     * 건물들을 감싸는 범위의 <b>지적도 필지</b>를 받는다.
     *
     * <p>어느 필지가 이 단지 땅인지는 <b>PostGIS 가 고른다</b>(건물과 실제로 겹치는지). 여기서는
     * 넉넉히 받아 넘기기만 한다 — 범위를 건물 외곽 + 40m 로 두어 단지 경계에 걸친 필지도 담는다.
     */
    private List<Map<String, String>> fetchParcelsAround(List<String> buildingGeoJsons) throws Exception {
        double[] box = extentOf(buildingGeoJsons);
        if (box == null) {
            return List.of();
        }
        String geomFilter = String.format(
                java.util.Locale.ROOT, "BOX(%.7f,%.7f,%.7f,%.7f)",
                box[0] - LON_PAD, box[1] - LAT_PAD, box[2] + LON_PAD, box[3] + LAT_PAD);

        String url = VWORLD_DATA_URL
                // size 는 300 으로 둔다 — 조밀한 구시가에서는 이 범위에 필지가 300개를 넘기도
                // 하지만(대치동 실측), 응답이 166KB 라 더 키우면 전국 4.4만 단지에서 부담이 된다.
                + "?service=data&request=GetFeature&data=" + LAYER_PARCEL + "&format=json&size=300"
                + "&crs=EPSG:4326&geometry=true&attribute=true"
                + "&geomFilter=" + enc(geomFilter)
                + "&domain=" + enc(vworldDomain)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("QUOTA") || body.contains("OVER_LIMIT")) {
            throw new QuotaExceededException();
        }

        // **건물 범위에 걸치지 않는 필지는 여기서 버린다.** 조밀한 구시가에서는 이 범위에 필지가
        // 300개(166KB)까지 오는데, 그걸 전부 DB 로 보내면 폴리곤 수십만 좌표가 인터넷을 건너가
        // 단지 하나에 3초가 걸린다(2026-10-21 실측 분당 19개). 여기서 사각 범위만 비교해 거르고,
        // **어느 필지가 이 단지 땅인지는 그대로 PostGIS 가 정확히 판단**한다.
        List<Map<String, String>> all = pickParcels(body);
        List<Map<String, String>> near = new java.util.ArrayList<>();
        for (Map<String, String> parcel : all) {
            double[] pb = extentOf(List.of(parcel.get("geom")));
            if (pb != null && boxesOverlap(pb, box, NEAR_PAD)) {
                near.add(parcel);
            }
        }
        return near;
    }

    /** 두 경위도 사각 범위가 (여유 {@code pad} 만큼) 겹치는가. */
    private static boolean boxesOverlap(double[] a, double[] b, double pad) {
        return !(a[2] + pad < b[0] || b[2] + pad < a[0] || a[3] + pad < b[1] || b[3] + pad < a[1]);
    }

    /**
     * GeoJSON 조각들을 감싸는 경위도 범위 {@code [minLon, minLat, maxLon, maxLat]}.
     *
     * <p>JSON 을 객체로 만들지 않고 <b>좌표쌍만</b> 집어낸다(폴리곤 하나가 수십 KB 다).
     * 우리 좌표는 한국이므로 경도 124~133 · 위도 30~39 꼴만 좌표로 본다 — 그 밖의 숫자
     * (높이·식별자)가 섞여 들어오지 않게 하는 장치다.
     */
    private static double[] extentOf(List<String> geoJsons) {
        java.util.regex.Matcher m;
        double minLon = Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        boolean found = false;
        for (String one : geoJsons) {
            m = COORD_PAIR.matcher(one);
            while (m.find()) {
                double lon = Double.parseDouble(m.group(1));
                double lat = Double.parseDouble(m.group(2));
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                found = true;
            }
        }
        return found ? new double[] {minLon, minLat, maxLon, maxLat} : null;
    }

    /** 필지 응답에서 {@code (pnu, geometry)} 묶음만 꺼낸다. 한 피처 안에서만 짝을 찾는다. */
    private static List<Map<String, String>> pickParcels(String json) {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int at = json.indexOf("\"geometry\"", from);
            if (at < 0) {
                break;
            }
            String geom = pickGeometry(json.substring(at));
            if (geom == null) {
                break;
            }
            int next = json.indexOf("\"geometry\"", at + 10);
            String chunk = next < 0 ? json.substring(at) : json.substring(at, next);
            String pnu = pickText(chunk, "pnu");
            Map<String, String> one = new LinkedHashMap<>();
            one.put("geom", geom);
            one.put("pnu", pnu == null ? "" : pnu);
            list.add(one);
            from = at + 10;
        }
        return list;
    }

    /** 지번 주소로 PNU(필지 고유번호 19자리)를 받는다. **시도·시군구 이름을 반드시 붙인다.** */
    private String lookupPnu(Map<String, Object> row) throws Exception {
        String umdNm = str(row.get("umd_nm"));
        String jibun = str(row.get("jibun"));
        if (umdNm.isBlank() || jibun.isBlank()) {
            return "";
        }
        // "개포동 141" 만 보내면 전국에 같은 동 이름이 있어 엉뚱한 곳이 잡힌다.
        String address = (str(row.get("sido_nm")) + " " + str(row.get("sgg_nm")) + " " + umdNm + " " + jibun).trim();

        String url = VWORLD_URL
                + "?service=address&request=getcoord&version=2.0&crs=epsg:4326&type=PARCEL"
                + "&address=" + enc(address)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("QUOTA") || body.contains("\"status\" : \"ERROR\"") && body.contains("LIMIT")) {
            throw new QuotaExceededException();
        }
        String pnu = pickText(body, "level4LC");
        return pnu == null ? "" : pnu;
    }

    /**
     * 행안부 호출 — 속도 제한(E0007)은 <b>잠깐 쉬면 풀린다</b>. 그 자리에서 몇 번 쉬었다 다시 부른다.
     *
     * <p>이걸 "좌표 없음"으로 넘기면 멀쩡한 단지가 실패로 쌓이고(실제로 열에 일곱이 그렇게 실패했다),
     * 반대로 곧장 한도로 보고하면 드라이버가 30분씩 멈춘다(그것도 실제로 겪었다).
     * <b>행안부를 부르는 곳은 모두 이 메서드를 거칠 것.</b>
     */
    private String callJuso(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            String body = call(url);
            if (!isThrottled(body)) {
                return body;
            }
            if (attempt >= THROTTLE_BACKOFF_MS.length) {
                // 쉬어도 안 풀린다 — 하루 한도로 보고 멈춘다(건너뛰면 그만큼 좌표가 빈다).
                throw new QuotaExceededException();
            }
            log.warn("좌표 API 속도 제한 — {}초 쉬었다 다시 시도합니다", THROTTLE_BACKOFF_MS[attempt] / 1000);
            sleep(THROTTLE_BACKOFF_MS[attempt]);
        }
    }

    /**
     * 행안부 좌표제공 응답의 <b>건물관리번호(bdMgtSn)</b> 앞 19자리로 PNU 를 만든다.
     *
     * <p>건물관리번호는 법정동코드(10) + 산구분(1) + 본번(4) + 부번(4) + 일련번호(6) 다.
     * 앞 19자리가 그 건물이 실제로 서 있는 땅의 PNU 이므로, <b>실거래의 옛 지번보다 믿을 수 있다.</b>
     */
    private String pnuFromRoadAddress(Map<String, Object> row) throws Exception {
        String admCd = str(row.get("sgg_cd")) + str(row.get("umd_cd"));
        String rnMgtSn = str(row.get("road_nm_sgg_cd")) + str(row.get("road_nm_cd"));
        String buldMnnm = trimZero(str(row.get("road_nm_bonbun")));
        String buldSlno = trimZero(str(row.get("road_nm_bubun")));
        String udrtYn = str(row.get("road_nmb_cd"));
        if (udrtYn.isBlank()) {
            udrtYn = "0";
        }
        if (jusoKey == null || jusoKey.isBlank()
                || admCd.length() != 10 || rnMgtSn.length() != 12 || buldMnnm.isBlank()) {
            return "";
        }

        String url = JUSO_URL
                + "?confmKey=" + enc(jusoKey)
                + "&admCd=" + enc(admCd)
                + "&rnMgtSn=" + enc(rnMgtSn)
                + "&udrtYn=" + enc(udrtYn)
                + "&buldMnnm=" + enc(buldMnnm)
                + "&buldSlno=" + enc(buldSlno)
                + "&resultType=json";

        String body = callJuso(url);
        String bdMgtSn = pickText(body, "bdMgtSn");
        return (bdMgtSn == null || bdMgtSn.length() < 19) ? "" : bdMgtSn.substring(0, 19);
    }

    /**
     * 그 땅 위의 건물들(아파트 동)을 GeoJSON 조각으로 받는다.
     *
     * <p>건물관리번호 앞 19자리가 PNU 이므로 {@code like} 로 그 땅의 건물만 고른다.
     * 단지 하나가 수십 동이라 넉넉히 받는다(래미안안양메가트리아 35동).
     */
    private List<String> fetchBuildingGeoJsons(String pnu) throws Exception {
        String url = VWORLD_DATA_URL
                + "?service=data&request=GetFeature&data=" + LAYER_BUILDING + "&format=json&size=300"
                + "&crs=EPSG:4326&geometry=true&attribute=false"
                + "&attrFilter=" + enc("bd_mgt_sn:like:" + pnu)
                + "&domain=" + enc(vworldDomain)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("QUOTA") || body.contains("OVER_LIMIT")) {
            throw new QuotaExceededException();
        }
        return pickGeometries(body);
    }

    /** PNU 로 연속지적도 필지를 꺼내 폴리곤 GeoJSON 만 돌려준다(건물이 없을 때의 대비책). */
    private String fetchParcelGeoJson(String pnu) throws Exception {
        String url = VWORLD_DATA_URL
                + "?service=data&request=GetFeature&data=" + LAYER_PARCEL + "&format=json&size=1"
                + "&crs=EPSG:4326&geometry=true&attribute=false"
                + "&attrFilter=" + enc("pnu:=:" + pnu)
                + "&domain=" + enc(vworldDomain)
                + "&key=" + enc(vworldKey);

        String body = call(url);
        if (body.contains("QUOTA") || body.contains("OVER_LIMIT")) {
            throw new QuotaExceededException();
        }
        String one = pickGeometry(body);
        return one;
    }

    /** 응답 안의 모든 {@code "geometry"} 덩어리를 차례로 꺼낸다. */
    private static List<String> pickGeometries(String json) {
        List<String> parts = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int at = json.indexOf("\"geometry\"", from);
            if (at < 0) {
                break;
            }
            String one = pickGeometry(json.substring(at));
            if (one == null) {
                break;
            }
            parts.add(one);
            from = at + one.length();
        }
        return parts;
    }

    /**
     * 응답에서 {@code "geometry": { ... }} 덩어리만 잘라낸다.
     *
     * <p>JSON 라이브러리를 들이지 않으려고 중괄호 짝만 센다 — 좌표 배열이 수백 KB 라
     * 통째로 객체로 만들 이유가 없고, PostGIS 가 어차피 문자열을 받는다.
     */
    private static String pickGeometry(String json) {
        int at = json.indexOf("\"geometry\"");
        if (at < 0) {
            return null;
        }
        int start = json.indexOf('{', at);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 문자열 값 하나만 꺼낸다(공백이 섞인 브이월드 응답 형식도 받는다). */
    private static String pickText(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at);
        int from = json.indexOf('"', colon);
        int to = from < 0 ? -1 : json.indexOf('"', from + 1);
        return (from < 0 || to < 0) ? null : json.substring(from + 1, to);
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

    /**
     * 속도 제한·한도에 걸린 응답인가. 행안부는 <b>HTTP 200 에 오류 코드</b>로 알려준다.
     * E0007 짧은 시간 다량 요청 · E0037 한도 초과.
     */
    private static boolean isThrottled(String body) {
        return body.contains("\"errorCode\":\"E0007\"")
                || body.contains("\"errorCode\":\"E0037\"")
                || body.contains("다량")
                || body.contains("초과");
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
