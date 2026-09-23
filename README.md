# sj-lab-scheduler — 공공데이터 수집 배치

> 공공데이터포털·국가교통정보센터(ITS)·생활안전지도에서 데이터를 수집해 **PostGIS에 적재하고, 지도가 바로 쓸 수 있는 GeoJSON 뷰까지 만들어 두는** 배치 서비스입니다.
> 지도에 보이는 편의점·버스정류장·CCTV·약국·병원·관공서 레이어의 **데이터 공급자**입니다.

| | |
|---|---|
| **결과물** | https://sj-lab.co.kr/map/ 의 공공데이터 레이어 |
| **스택** | Java 17 · Spring Boot 3.3.2 · MyBatis · PostgreSQL/PostGIS · Spring Cloud(Eureka) · `@Scheduled` |
| **형태** | 화면 없음. cron 배치 + 수동 트리거용 REST |

---

## 1. 데이터 흐름

```
[외부 공공 API] 공공데이터포털 · ITS · 생활안전지도
        │ cron 수집(페이징)
        ▼
[이 서비스] 파싱 → DTO 변환 → 청크 분할 → UPSERT
        │ 적재 직후 CREATE OR REPLACE VIEW map.v_*_geojson
        ▼
[DB] PostgreSQL 17 + PostGIS 3.4
        │ select geojson
        ▼
[백엔드] mapservice-rest ──▶ [게이트웨이] ──▶ [지도]
```

**지도용 뷰의 정의 원본은 DB가 아니라 이 저장소의 매퍼 XML입니다.** 뷰를 고치면 `mapservice-rest`의 bbox 조회 SQL도 같은 작업에서 맞춰야 결과가 어긋나지 않는다는 점을 문서 규칙으로 남겼습니다.

---

## 2. 면접에서 봐주셨으면 하는 부분

### ① 수집 배치의 반복 패턴을 규격화

도메인이 늘어날수록 "외부 API 호출 → 페이징 → 파싱 → 타입 변환 → 적재"가 반복됩니다. 이 패턴을 **9단계 체크리스트로 문서화**하고 유틸로 뽑아, 새 배치를 추가할 때 같은 모양이 나오도록 했습니다.

- `DataTypeUtil.first/any` — 공공 API마다 제각각인 키 명명(대소문자·스네이크·카멜) 흡수
- `DataTypeUtil.s/i/l/d/decimal` — 실패 시 null을 반환하는 안전한 타입 변환(응답 필드가 문자열/숫자로 섞여 오는 문제)
- `DataTypeUtil.dedupeBy` — 응답 자체에 중복 PK가 섞여 오는 경우 대비

### ② 멱등한 적재 — `ON CONFLICT DO NOTHING RETURNING`

재실행해도 중복이 쌓이지 않도록 UPSERT로 적재하고, `RETURNING pk`의 개수로 **실제 삽입 건수**를 셉니다(요청 건수와 구분). 대량 삽입은 500~5,000건 단위로 청크 분할해 PostgreSQL 바인드 파라미터 상한과 메모리 문제를 피합니다.

### ③ 좌표계와 지도용 뷰

좌표는 `ST_MakePoint(lon, lat)` → `ST_Transform(4326, 3857)`로 저장해 지도 뷰(EPSG:3857)와 맞춥니다. 적재 직후 `CREATE OR REPLACE VIEW`로 `geojson` 컬럼을 가진 뷰를 재생성해, **API는 조립 없이 select만 하면 되도록** 했습니다.

### ④ 서비스 기동 시 1회 수집 — CCTV

CCTV 스트리밍 URL은 주기적으로 갱신돼야 재생됩니다. 서버를 재배포하면 다음 06:00 cron까지 영상이 안 나오는 문제가 있어, `ApplicationReadyEvent`로 **기동 직후 1회 수집**을 추가했습니다(별도 스레드로 띄워 기동을 막지 않음). 적재 결과 11,195건을 확인했습니다.

### ⑤ cron 시간표 관리

배치가 늘면서 시간대 충돌이 실제 위험이 됐습니다. 도메인별 실행 시각을 표로 유지하고, **새 배치를 추가할 때 이 표로 겹침을 확인한 뒤 표도 갱신**하는 것을 규칙으로 만들었습니다.

| 도메인 | cron | 도메인 | cron |
|---|---|---|---|
| 편의점 | 00:20 | 관공서 | 02:00 |
| 버스노선 | 00:40 | CCTV | 06:00 **+ 기동 시 1회** |
| 약국 | 01:20 | 아파트 매매 | 06:00 |
| 병원 | 01:40 | 아파트 전월세 | 07:00 |

모든 배치는 `@Scheduled` + 같은 경로의 `@RequestMapping`으로 **이중 노출**해 수동 재실행이 가능합니다(수집 실패 시 운영 대응용).

---

## 3. 실행

```bash
mvnw.cmd clean package     # target/sj-lab-scheduler.jar
mvnw.cmd spring-boot:run   # 랜덤 포트, context-path /scheduler
mvnw.cmd test
```

> ⚠️ **로컬에서 띄우면 cron이 실제 DB에 적재합니다.** 그래서 이 서비스만 로컬 통합 기동 스크립트에서 제외했습니다.

Eureka가 없어도 등록 실패 로그만 남고 앱은 정상 기동합니다.

---

## 4. 구조

```
Controller(@RestController + @Scheduled) → Service/ServiceImpl → Mapper(MyBatis) → mapper/*.xml
```

| 세트 | 출처 | 수집 대상 |
|---|---|---|
| **Apis** | 공공데이터포털 | 버스노선, 약국, 병원, 아파트 실거래, 공공기관 시설정보 |
| **Its** | 국가교통정보센터 | CCTV |
| **Safemap** | 생활안전지도 | 편의점, 관공서 |

| 스키마 | 내용 |
|---|---|
| `map.*` | 지리정보(PostGIS 좌표) + 지도용 `v_*_geojson` 뷰 |
| `ai.*` | 아파트 매매·전월세 실거래가 |

---

## 5. 배포

```
git push → Jenkins(빌드 → 이미지 push) → sj-lab-k8s-manifests 의 image.tag 자동 커밋
        → ArgoCD 동기화 → Kubernetes 롤아웃
```

---

## 6. 개선이 필요한 부분 (인지하고 있는 기술 부채)

포트폴리오이므로 솔직히 적습니다. 리팩터링 대상으로 파악하고 있는 항목들입니다.

- **컨트롤러가 너무 많은 일을 합니다.** 외부 호출·파싱·페이징이 컨트롤러에 있고 Service는 변환·적재만 합니다. 수집기(Collector) 계층 분리가 필요합니다.
- **트랜잭션 범위**: 컨트롤러 전체에 `@Transactional`이 걸려 있어, 분 단위가 걸리는 외부 호출이 DB 커넥션을 점유합니다. 외부 호출과 적재 트랜잭션 분리가 우선순위입니다.
- **비밀값**: `application.yml`에 DB 비밀번호·API 키가 평문으로 커밋돼 있습니다(초기 구성의 잔재). 외부 시크릿으로 이전해야 합니다.
- **인코딩 함정**: `apikey.apis`는 이미 URL 인코딩된 값이라 메서드마다 재인코딩 여부가 달라, 새 코드에서 이중 인코딩(`%` → `%25`) 사고가 나기 쉽습니다.
- 예외를 `printStackTrace()`로 끝내는 구간이 남아 있어 `log.error`로 통일 중입니다.

## 참고

- 전체 구조·API 계약: 총괄 저장소 `mapservice-rest`의 `docs/system-architecture.md`
- 수집 패턴 상세·cron 표: 이 저장소의 `CLAUDE.md`
