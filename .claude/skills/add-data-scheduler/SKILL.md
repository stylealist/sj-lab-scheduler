---
name: add-data-scheduler
description: sj-lab-scheduler에 새로운 공공 API 데이터 수집 스케줄러(신규 도메인)를 추가할 때 사용. Controller/Service/Mapper/XML을 이 저장소의 기존 관례에 맞춰 한 세트로 만들어준다.
---

이 저장소(sj-lab-scheduler)는 "외부 공공 API에서 데이터를 페이징 수집 → DTO 변환 → 배치 insert → (필요 시) 조회용 뷰 갱신"이라는 동일한 패턴을 도메인마다 반복한다. 새 수집 배치를 추가할 때는 아래 순서를 그대로 따르고, 반드시 기존 파일을 열어 실제 코드를 참고한다:

- 예시 컨트롤러: `src/main/java/com/example/scheduler/controller/ApisDataSchedulerController.java`의 `fcltList()` (가장 단순하고 최신 예시)
- 예시 서비스: `src/main/java/com/example/scheduler/service/impl/ApisDataServiceImpl.java`의 `insertFcltInfo()`
- 예시 매퍼 XML: `src/main/resources/mapper/ApisDataApi.xml`의 `insertFcltInfo` / `createFcltInfoView`
- 공통 유틸: `src/main/java/com/example/scheduler/util/DataTypeUtil.java`

## 진행 순서

1. **요구사항 확인**: 대상 공공 API의 base URL, 인증 방식(쿼리 파라미터 `serviceKey`인지, 이미 URL 인코딩된 키인지), 응답 포맷(XML/JSON), 페이지네이션 파라미터명(`pageNo`/`page`, `numOfRows`/`perPage`), 저장할 DB 스키마(`map.*` 지리정보 vs `ai.*` 그 외)를 사용자에게 확인하거나 기존 API 문서에서 파악한다.
2. **DTO 추가**: `src/main/java/com/example/scheduler/dto/`에 `XxxInfoDto`를 Lombok `@Builder`/`@Data`로 만든다. 필드명은 DB 컬럼과 매칭되는 camelCase로 통일한다.
3. **Mapper 인터페이스 추가**: 해당 도메인 그룹의 Mapper(`ApisDataMapper`/`ItsDataMapper`/`SafemapMapper`, 새 그룹이면 새 Mapper)에 insert 메서드를 추가한다.
   - 중복 방지가 필요하면 `List<String> insertXxx(@Param("list") List<XxxDto> list);` (RETURNING 패턴)
   - 단순 카운트만 필요하면 `int insertXxx(@Param("list") List<XxxDto> list);`
   - 조회용 뷰가 필요하면 `void createXxxView();`도 함께 선언한다.
4. **Mapper XML 작성**: 같은 그룹의 `mapper/*.xml`에 `<insert>`/`<select resultType="string">`을 추가한다.
   - `INSERT INTO <schema>.<table> (...) VALUES <foreach collection="list" item="i" separator=","> (...) </foreach> ON CONFLICT (pk컬럼) DO NOTHING [RETURNING pk컬럼]`
   - 좌표 데이터면 `ST_Transform(ST_SetSRID(ST_MakePoint(lon, lat), 4326), 3857)`로 3857 좌표계로 변환해 저장한다(기존 `bus_stop_info` 패턴 참고).
   - 조회용 뷰가 필요하면 `createXxxView`에 `CREATE OR REPLACE VIEW "map".v_xxx AS SELECT ... FROM <table> WHERE geom IS NOT NULL`을 작성한다.
5. **ServiceImpl 작성**: `insertXxx(List<Map<String,Object>> item)`을 추가한다.
   - `Map → DTO` 변환은 `DataTypeUtil.first/any`로 API 응답의 대소문자/명명 차이를 흡수하고, `s/i/l/d/decimal/toInt/toLong/toDouble/toStr`로 안전 변환한다.
   - 500~5,000건 단위로 `List#subList`를 이용해 chunk 분할 후 Mapper 호출한다(파라미터 상한/메모리 보호).
   - API 응답 자체에 중복 PK가 섞여 올 수 있으면 insert 전에 `DataTypeUtil.dedupeBy(list, KeyDto::getPk)`로 정리한다.
   - 뷰가 있는 도메인이면 insert 후 `mapper.createXxxView()`를 호출하고 실패해도 전체 흐름은 죽지 않게 try/catch로 감싼다(`insertFcltInfo` 참고).
6. **Controller 작성**: 해당 그룹 컨트롤러(또는 새 그룹이면 새 `XxxSchedulerController`)에 메서드를 추가한다.
   - `@Scheduled(cron = "...")` + 동일 경로의 `@RequestMapping("/...")`을 함께 붙여 수동 트리거도 가능하게 한다.
   - cron 시각은 CLAUDE.md의 "도메인별 스케줄 시각" 표에 있는 기존 배치들과 겹치지 않는 시간으로 고른다.
   - 외부 호출은 `HttpURLConnection` 직접 사용(XML이면 `DataConverter.xmlToList` 재사용, JSON이면 이 컨트롤러 전용 private 파서 메서드를 새로 작성). 서비스키가 이미 URL 인코딩되어 있는지 확인하고, 아니라면만 `URLEncoder.encode`를 적용한다.
   - `totalCount`/`numOfRows` 기반으로 반복 페이징하고, 응답이 비었거나 요청 건수보다 적게 오면 종료하는 조건을 반드시 넣는다.
   - 예외는 `log.error("...", e)`로 남긴다(`e.printStackTrace()` 지양).
7. **문서 갱신**: CLAUDE.md의 "도메인별 스케줄 시각" 표에 새 배치를 한 줄 추가한다.
8. **검증**: `reviewer` 서브에이전트로 diff를 리뷰시키거나, 최소한 `./mvnw.cmd -q compile`로 컴파일이 되는지 확인한다. DB 연결이 필요한 실제 수집 실행은 사용자 승인 없이 프로덕션 DB에 대고 임의로 트리거하지 않는다.
