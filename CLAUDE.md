# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

Spring Boot 3.3.2 기반 REST + 스케줄러 서비스. 공공데이터포털, 국가교통정보센터(ITS), 안전지도(Safemap) 등 외부 공공 API에서 데이터를 수집해 PostgreSQL/PostGIS DB에 적재하는 배치성 백엔드다. Eureka에 등록되는 MSA 구성원 중 하나(`sj-lab-scheduler`)이며, 자체 화면 없이 스케줄러 + 관리용 REST 엔드포인트만 제공한다.

## 빌드 및 실행

- 빌드: `./mvnw.cmd clean package` (Windows) / `./mvnw clean package`
- 로컬 실행: `./mvnw.cmd spring-boot:run` 또는 `java -jar target/sj-lab-scheduler.jar`
- 전체 테스트: `./mvnw.cmd test`
- 단일 테스트 클래스: `./mvnw.cmd test -Dtest=NewFirstServiceApplicationTests`
- Docker 이미지: `docker build -t sj-lab-scheduler .` (Dockerfile은 `target/sj-lab-scheduler.jar`를 그대로 복사하므로 반드시 먼저 `package`로 jar를 만들어야 함)
- `server.port: 0`(랜덤 포트) + `context-path: /scheduler` 조합으로 기동되며, Eureka(`http://localhost:8761/eureka`)가 없으면 등록 실패 로그만 나고 앱 자체는 정상 기동됨
- `local` 프로파일(`application-local.yml`)은 Eureka 설정과 Swagger UI 프록시 경로(`/scheduler/v3/api-docs`)만 오버라이드함

## 아키텍처

계층 흐름은 `Controller(@RestController + @Scheduled) → Service/ServiceImpl → Mapper(MyBatis interface) → mapper/*.xml(SQL)` 이다. DTO는 Lombok `@Builder`/`@Data` 조합을 쓴다. 다만 이 프로젝트는 controller-heavy 구조로, 외부 API 호출·XML/JSON 파싱·페이징 수집 로직까지 컨트롤러(예: `ApisDataSchedulerController`)에 들어 있고, Service는 주로 `Map → DTO` 변환과 배치 insert만 담당한다.

도메인은 3개 컨트롤러/매퍼 세트로 나뉜다:
- **Apis** (`ApisDataSchedulerController`/`ApisDataService`/`ApisDataMapper`): 공공데이터포털 — 버스노선, 약국, 병원, 아파트 매매·전월세, 공공기관 시설정보
- **Its** (`ItsDataSchedulerController`/`ItsDataService`/`ItsDataMapper`): 국가교통정보센터 — CCTV
- **Safemap** (`SafemapSchedulerController`/`SafemapService`/`SafemapMapper`): 생활안전지도 — 편의점, 관공서

### 새 수집 스케줄러를 추가할 때 반드시 따라야 하는 반복 패턴

새 배치를 추가하기 전에 `ApisDataSchedulerController.fcltList()` (가장 최근/깔끔한 예시)와 `ApisDataServiceImpl.insertFcltInfo()`를 먼저 참고할 것.

1. `@Scheduled(cron = "...")`와 동일 경로의 `@RequestMapping`을 함께 붙여, 수동 트리거도 가능하게 이중 노출한다.
2. 외부 호출은 `HttpURLConnection`을 직접 사용한다. XML 응답은 `DataConverter.xmlToList(StringBuilder)`로, JSON 응답은 컨트롤러별 private 파서 메서드로 `Map<String,Object>`/`List<Map<String,Object>>`로 변환한다.
3. `totalCount`/`numOfRows`(또는 `perPage`)로 페이지 수를 계산해 반복 수집한다. 페이지당 1,000~10,000건, 각 API의 상한을 넘기지 않도록 주의.
4. `Map → DTO` 변환 시 `DataTypeUtil`의 `first/any`(키 대소문자·명명규칙 차이 흡수), `s/i/l/d/decimal/toInt/toLong/toDouble/toStr`(안전한 타입 변환, 실패 시 null)을 사용한다. 신규 필드 매핑도 이 유틸을 그대로 재사용할 것.
5. Service에서 500~5,000건 단위로 `chunk` 분할 후 Mapper를 호출한다(PostgreSQL 바인드 파라미터 상한 및 메모리 보호 목적).
6. Mapper XML은 `INSERT ... ON CONFLICT (pk컬럼) DO NOTHING RETURNING pk컬럼` 패턴으로 중복 삽입을 막고, 반환된 리스트의 `size()`로 실제 삽입 건수를 센다(이 경우 Mapper 인터페이스 리턴 타입은 `List<String>`). 단순 삽입 건수만 필요하면 `int` 리턴 + `RETURNING` 없이 처리해도 된다(`insertFcltInfo` 참고).
7. 좌표 저장 도메인(`bus_stop_info` 등)은 `ST_MakePoint(lon, lat)` → `ST_Transform(4326, 3857)`로 좌표계를 변환해 저장한다.
8. 적재 후 지도 조회용 뷰(`map.v_xxx`, `map.v_xxx_geojson`)를 쓰는 도메인은 insert 직후 `CREATE OR REPLACE VIEW ...`를 실행하는 매퍼 메서드(`createFcltInfoView`, `insertApisBusStopInfoGeoJson` 등)를 이어서 호출한다.
9. 자체 중복 제거가 필요하면 `DataTypeUtil.dedupeBy(list, keyExtractor)`를 사용한다(API 응답 자체에 중복 PK가 섞여 오는 경우 대비).

### DB 스키마

- `map.*`: 버스노선/정류장, 편의점, 관공서, 공공시설 등 지리정보(PostGIS 좌표 포함)
- `ai.*`: 아파트 매매(`apt_trades`)/전월세(`apt_rents`) 실거래가

## 참고: 인증키·URL 인코딩 주의사항

- 외부 연동 키(`apikey.safe`/`apikey.apis`/`apikey.its`)는 `application.yml`에 값이 박혀 있고 `@Value`로 주입해서 쓴다.
- `apikey.apis` 값은 이미 URL 인코딩된 형태로 저장돼 있다. `ApisDataSchedulerController.busRouteInfo()`는 `apisServiceKey.contains("%")`로 기인코딩 여부를 분기해서 이중 인코딩을 피하지만, 같은 컨트롤러의 다른 메서드(`pharmacy`, `hospital`, `aptTrades`, `aptRents`)는 항상 `URLEncoder.encode(apisServiceKey, "UTF-8")`를 다시 호출한다 — 이미 인코딩된 키를 한 번 더 인코딩하면 `%`가 `%25`로 깨져 API 호출이 실패할 수 있으니, 새 코드 작성 시 어느 방식이 맞는지 반드시 확인 후 맞출 것.

## 참고: 도메인별 스케줄 시각(cron)

| 도메인 | 메서드 | cron | 비고 |
|---|---|---|---|
| 버스노선 | `ApisDataSchedulerController.busRouteInfo` | 매일 00:40 | |
| 편의점 | `SafemapSchedulerController.convenienceStore` | 매일 00:20 | |
| 약국 | `ApisDataSchedulerController.pharmacy` | 매일 01:20 | |
| 병원 | `ApisDataSchedulerController.hospital` | 매일 01:40 | |
| 관공서 | `SafemapSchedulerController.governmentOffice` | 매일 02:00 | |
| CCTV | `ItsDataSchedulerController.cctvInfo` | 매일 06:00 | **서버 기동 시에도 1회 자동 실행**(`config/CctvStartupRunner`, `ApplicationReadyEvent`) — CCTV 스트리밍 URL이 주기적으로 갱신돼야 재생되므로, 다음 06:00까지 기다리지 않게 함 |
| 아파트 매매 | `ApisDataSchedulerController.aptTrades` | 매일 06:00 | 이번 달+저번 달 재조회 |
| 아파트 전월세 | `ApisDataSchedulerController.aptRents` | 매일 07:00 | 이번 달+저번 달 재조회 |
| 공공기관 시설정보 | `ApisDataSchedulerController.fcltList` | 수동(`/apis/fclt/list`), cron 주석 처리됨 | 08:00 예정이었으나 현재 비활성 |
| 버스정류장 위치 | (주석 처리됨) | - | 공공데이터포털 스펙 변경으로 엑셀 다운로드 방식 전환 필요 |

새 스케줄을 추가/변경할 때는 다른 작업과 겹치지 않는 시간대인지 이 표를 기준으로 확인하고, 표도 함께 갱신할 것.

## 참고: 보안/코드 품질상 알려진 이슈

- `application.yml`/`application-local.yml`에 실제 DB 비밀번호와 외부 API 키가 평문으로 커밋되어 git 이력에 남아 있다. 이 파일들을 수정할 때 추가 노출을 만들지 않도록 주의하고, 키/비밀번호 로테이션이나 외부 시크릿 관리로의 이전은 사용자와 상의 없이 임의로 진행하지 말 것.
- `SecurityConfig`는 CSRF만 비활성화하고 `h2-console` 경로만 인증 예외 처리할 뿐, 그 외 엔드포인트에 대한 인증/인가 설정이 없어 사실상 전체 오픈 상태다.
- 예외 처리가 `log.error` 대신 `e.printStackTrace()`로만 끝나는 메서드가 다수 섞여 있다(`DataConverter`의 일부 경로, 각 컨트롤러의 private XML/JSON 파서들). 신규 코드는 `log.error`로 통일할 것.
- 컨트롤러 클래스 전체에 `@Transactional`이 걸려 있는데, 메서드 본문이 분(단위)이 걸릴 수 있는 외부 API 다중 페이지 호출을 포함한다. DB 커넥션을 불필요하게 오래 점유할 수 있으므로, 새 배치를 만들 때는 외부 호출과 DB insert 트랜잭션 범위를 분리하는 것을 고려할 것.

## 통합 허브

저장소를 넘나드는 작업(DB → 백엔드 → 디스커버리 → 게이트웨이 → 프론트엔드)의 총괄 기준 저장소는 `C:\developer\workspace\mapservice-rest`입니다. 시스템 전체 구조·API 계약은 그 저장소의 `docs/system-architecture.md`, 로컬 포트·기동 순서·CORS는 `docs/dev-environment.md`에 있고, MCP(GitHub/DB)와 로컬 비밀값도 그 저장소에서만 관리합니다.