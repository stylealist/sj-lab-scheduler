# sj-lab-scheduler — 공공데이터 수집 및 공간 DB 적재 배치 서비스

`sj-lab-scheduler`는 공공데이터포털, 국가교통정보센터(ITS), 생활안전지도 등 외부 공공 API로부터 시설물 및 실거래가 데이터를 주기적으로 수집·정제하여 PostGIS에 적재하고, 지도 서비스(`mapservice-rest`)가 즉시 활용할 수 있는 GeoJSON 공간 뷰를 자동 생성·관리하는 배치 애플리케이션입니다.

---

## 1. 서비스 역할 및 핵심 책임

- **공공 API 데이터 수집 및 정제**: 비정형/다양한 규격의 외부 공공 API(XML/JSON, 대소문자 혼용, 인코딩 차이)를 일관된 DTO 규격으로 파싱 및 타입 정제합니다.
- **PostGIS 공간 데이터 변환 및 적재**: 경위도 좌표(EPSG:4326)를 웹 지도 투영 좌표계(EPSG:3857)로 변환하여 지리정보 테이블에 멱등하게(Idempotent) UPSERT 적재합니다.
- **지도 서빙용 GeoJSON 뷰 자동 생성**: 데이터 수집 직후 DB 레벨에서 `CREATE OR REPLACE VIEW map.v_*_geojson`을 실행하여, 백엔드 API가 복잡한 공간 연산이나 DTO 변환 없이 `geojson` 텍스트를 즉시 반환할 수 있도록 최적화된 공간 뷰를 제공합니다.
- **주기적 크론(Cron) 스케줄링 및 수동 트리거 지원**: 정기적인 배치 실행 외에도 운영 중 즉시 동기화가 필요한 경우를 위해 REST 엔드포인트를 통한 수동 실행을 병행 지원합니다.

---

## 2. 기술 스택

- **언어 및 런타임**: Java 17, Spring Boot 3.3.2
- **데이터베이스 및 공간 엔진**: PostgreSQL 17, PostGIS 3.4 (MyBatis 3.0 매퍼 연동)
- **배치 및 스케줄링**: Spring `@Scheduled`, Spring Cloud Netflix Eureka Client
- **배포 환경**: Docker, Kubernetes, Helm, Jenkins CI, ArgoCD (GitOps)

---

## 3. 데이터 파이프라인 및 업무 프로세스

### 3.1 전체 파이프라인 흐름도

```
[공공데이터포털]       [국가교통정보센터 (ITS)]     [생활안전지도]
(약국·병원·버스노선)        (고속도로 CCTV)         (편의점·관공서)
       │                      │                      │
       └──────────────────────┼──────────────────────┘
                              ▼
           [sj-lab-scheduler] (Spring Boot)
             ├── 1. 외부 API 호출 및 페이징 수집
             ├── 2. DataTypeUtil 기반 정제 및 타입 변환
             ├── 3. 청크(Chunk 500~5,000건) 분할
             ├── 4. ST_Transform(EPSG:3857) 공간 변환
             └── 5. ON CONFLICT DO NOTHING UPSERT 적재
                              │
                              ▼ 적재 완료 트리거
           [PostgreSQL 17 / PostGIS 3.4]
             ├─ 지리정보 원본 테이블 (map.*, ai.*)
             └─ CREATE OR REPLACE VIEW map.v_*_geojson
                              │
                              ▼ GeoJSON 뷰 select
           [mapservice-rest] ──> [웹 지도 클라이언트]
```

### 3.2 수집 대상 및 스케줄링 시간표 (Cron Schedule)

| 도메인 | 데이터 출처 | 실행 주기 (Cron) | 적재 대상 테이블 | 생성되는 지도 뷰 |
|---|---|---|---|---|
| **편의점** | 생활안전지도 | 매일 00:20 | `map.safemap_cvs_info` | `map.v_cvs_info_geojson` |
| **버스노선** | 공공데이터포털 | 매일 00:40 | `map.apis_bus_route_info` | `map.v_bus_route_geojson` |
| **약국** | 공공데이터포털 | 매일 01:20 | `map.apis_pharmacy_info` | `map.v_pharmacy_info_geojson` |
| **병원** | 공공데이터포털 | 매일 01:40 | `map.apis_hospital_info` | `map.v_hospital_info_geojson` |
| **관공서** | 생활안전지도 | 매일 02:00 | `map.safemap_gov_info` | `map.v_gov_info_geojson` |
| **CCTV** | 국가교통정보센터 | 매일 06:00 **+ 기동 시 1회** | `map.its_cctv_info` | `map.v_cctv_info_geojson` |
| **아파트 매매** | 공공데이터포털 | 매일 06:00 | `ai.apartment_sale_trade` | 실거래 분석 통계 테이블 |
| **아파트 전월세**| 공공데이터포털 | 매일 07:00 | `ai.apartment_rent_trade` | 전월세 동향 통계 테이블 |

---

## 4. 핵심 엔지니어링 구현 상세

### 4.1 멱등성 보장 및 대량 배치 청크 처리 (Chunk Partitioning)
- 대량 수집 데이터 적재 시 네트워크 순단이나 부분 실패로 인한 재실행이 발생해도 데이터가 중복 적재되지 않도록 `ON CONFLICT (pk) DO NOTHING` 기반의 UPSERT를 적용합니다.
- PostgreSQL의 바인드 파라미터 한계(`65,535개`)와 JVM 힙 메모리 압박을 방지하기 위해, 파싱된 데이터를 500~5,000건 단위의 청크로 분할하여 배치 실행합니다.
- 실제 신규 반영 건수를 추적하기 위해 `RETURNING pk` 구문을 활용하여 요청 건수와 실제 삽입 건수를 명확히 분리 집계합니다.

### 4.2 CCTV 실시간 스트리밍 특화 기동 시 즉시 수집
CCTV 실시간 스트리밍 URL은 주기적으로 토큰 및 엔드포인트가 갱신되어야 브라우저 재생이 가능합니다. Pod 재배포 시 다음 정기 크론(06:00)까지 스트리밍이 중단되는 현상을 방지하기 위해:
- `ApplicationReadyEvent` 리스너를 등록하여 애플리케이션 기동 완료 즉시 비동기 워커 스레드를 통해 CCTV 데이터를 1회 자동 수집(약 11,000건)합니다.

### 4.3 이기종 공공 API 응답 정규화 (`DataTypeUtil`)
기관마다 다른 JSON/XML 구조와 키 명명 규칙(카멜/스네이크/대소문자 불일치), 문자열로 넘어오는 숫자 필드를 안전하게 처리하는 유틸리티 파이프라인을 구축했습니다.
- `DataTypeUtil.first / any`: 다양한 키 후보 중 존재하는 값을 우선 획득
- `DataTypeUtil.s / i / l / d / decimal`: 파싱 실패 시 예외 대신 `null`을 반환하는 방어적 타입 변환
- `DataTypeUtil.dedupeBy`: 외부 API 응답 내 중복 키 사전 제거

---

## 5. 실행 및 개발 환경

### 로컬 빌드 및 실행
```powershell
# Maven 빌드
mvnw.cmd clean package

# 로컬 실행 (랜덤 포트, context-path: /scheduler)
mvnw.cmd spring-boot:run
```

> **주의**: 로컬 환경에서 기동 시 설정된 크론에 의해 실제 데이터베이스로 적재가 발생할 수 있으므로, 단독 검증 시 데이터 소스 설정을 점검하십시오.

### 수동 배치 트리거 API
배치 메서드는 `@Scheduled`와 함께 동일 경로의 `@RequestMapping`을 제공하여 필요 시 수동 트리거가 가능합니다:
```bash
# 편의점 데이터 수동 수집
curl -X POST http://localhost:8100/scheduler/cvs/collect

# CCTV 데이터 수동 수집
curl -X POST http://localhost:8100/scheduler/cctv/collect
```
