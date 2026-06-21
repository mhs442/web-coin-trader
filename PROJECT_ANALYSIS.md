# Web Coin Trader - 프로젝트 분석 문서

> 이 문서는 세션 간 컨텍스트 재분석을 줄이기 위해 작성되었다.
> 최초 작성: 2026-06-16

---

## 1. 프로젝트 개요

**Bybit API 기반 암호화폐 자동매매 웹 애플리케이션**

- **Java 17 + Spring Boot 3.4.2**
- **빌드 도구**: Gradle
- **DB**: MariaDB (운영), H2 (테스트)
- **서버 포트**: 8080
- **DDL 전략**: `ddl-auto: none` — 스키마 변경 시 반드시 직접 SQL 작성 필요

---

## 2. 기술 스택

| 구분 | 기술 |
|------|------|
| Backend | Java 17, Spring Boot 3.4.2 |
| Frontend | Thymeleaf, jQuery 4.0.0, ApexCharts |
| Database | MariaDB / H2 (테스트) |
| API 통신 | Spring Cloud OpenFeign (선언형 HTTP 클라이언트) |
| 실시간 | WebSocket (Bybit 시세/Kline 스트림), STOMP (서버→브라우저 push) |
| 인증 | Spring Security (폼 로그인, BCrypt) |
| 암호화 | AES-256 (Bybit API Key/Secret 저장) |
| 테스트 | JUnit 5, WireMock (API 모의) |

---

## 3. 전체 패키지 구조

```
src/main/java/com/coin/webcointrader/
├── WebCoinTraderApplication.java
│
├── autotrade/                  ★ 자동매매 엔진 (핵심)
│   ├── controller/             AutoTradeController
│   ├── service/                AutoTradeService, PatternQueueService
│   ├── dto/                    QueueStateDTO, TradePhase, AutoTradeSessionDTO,
│   │                           AutoTradeAlertResponse, AutoTradeStatusResponse,
│   │                           PatternQueueResponse, AddPatternRequest, UpdatePatternRequest, CopyQueueRequest
│   └── repository/             PatternQueueRepository, TradeHistoryRepository, InvestmentHistoryRepository
│
├── common/                     공통 레이어
│   ├── entity/                 JPA 엔티티 (12개, 아래 섹션 참고)
│   ├── enums/                  TradeMode, OrderResult, TradeOrderType, Category,
│   │                           ExceptionMessage, LogMessage
│   ├── dto/
│   │   ├── request/            CreateOrderRequest, SetLeverageRequest,
│   │   │                       SetMarginModeRequest, SetTradingStopRequest
│   │   └── response/           CreateOrderResponse, FindTickerResponse, GetKlineResponse,
│   │                           GetPositionListResponse, GetWalletBalanceResponse,
│   │                           GetInstrumentsInfoResponse, OrderBookResponse,
│   │                           SetLeverageResponse, SetMarginModeResponse,
│   │                           SetTradingStopResponse, PageResponse
│   ├── client/
│   │   ├── market/             MarketClient (OpenFeign), BybitWebSocketClient
│   │   │   └── dto/            WebSocketTickerDTO, WebSocketKlineDTO, WebSocketRequest
│   │   ├── trade/              TradeClient (OpenFeign)
│   │   ├── account/            AccountClient (OpenFeign)
│   │   └── position/           PositionClient (OpenFeign)
│   ├── config/                 SecurityConfig, BybitFeignConfig, JpaConfig,
│   │                           WebSocketConfig, WebSocketStompConfig, ExceptionAdvice
│   ├── exception/              CustomException
│   └── util/                   AesEncryptor, UserApiKeyContext
│
├── login/                      로그인 + 회원가입 통합
│   ├── controller/             LoginController
│   ├── service/                LoginService, BybitApiKeyValidator
│   ├── repository/             LoginRepository
│   └── dto/                    SignupRequest, QueryApiKeyResponse
│
├── market/                     시장 데이터
│   ├── controller/             MarketController
│   └── service/                MarketService
│
├── mypage/                     마이페이지
│   ├── controller/             MyPageController, MyPageApiController
│   ├── service/                MyPageService
│   └── dto/                    TradeHistoryRequest/Response, InvestmentHistoryRequest/Response,
│                               InvestmentHistoryPageResponse, InvestmentSummaryResponse,
│                               MyPagePatternRequest/Response, DeleteHistoryRequest
│
├── trade/                      거래 실행 Facade
│   ├── controller/             TradeController
│   └── service/                TradeFacade, TradeService
│
├── sim/                        모의투자
│   ├── controller/             SimWalletController
│   ├── service/                SimTradeService, SimWalletService
│   ├── repository/             SimTradeHistoryRepository, SimInvestmentHistoryRepository, SimWalletRepository
│   └── dto/                    AddBalanceRequest
│
└── wallet/                     실전 지갑
    ├── controller/             WalletApiController
    └── service/                WalletService
```

---

## 4. 도메인별 핵심 역할

### autotrade — 자동매매 엔진

**AutoTradeService** (`autotrade/service/AutoTradeService.java`)
- 활성 세션을 `ConcurrentHashMap<String, AutoTradeSessionDTO>` 로 관리. 키: `"userId:symbol"`
- `@PostConstruct` 에서 DB 활성 큐를 복원하고 WebSocket 콜백 등록
- `@Scheduled` 1초 스케줄러는 WebSocket 장애 대비 fallback (isWsActive 체크로 중복 방지)
- 3-Phase 로직 (아래 섹션 상세 참고)
- 주문 후 STOMP push: `/topic/autotrade.log.{symbol}`, `/topic/autotrade.alert.{symbol}`
- 상수: `TAKER_FEE_RATE = 0.00055`, `BLOCK_WAIT_SECONDS = 60`

**PatternQueueService** (`autotrade/service/PatternQueueService.java`)
- 큐 CRUD + 활성화 토글
- 큐 구조 검증: 단계 1~20개 / 패턴 단계당 최대 2개 / 블록 패턴당 최대 5개 / 리프 블록 필수
- 심볼당 활성 큐 1개 제한 (중복 매매 방지)

**QueueStateDTO** (`autotrade/dto/QueueStateDTO.java`)
- 큐 런타임 상태 전체를 담는 객체
- `AtomicBoolean processing` 으로 중복 주문 방지 (tryLock / unlock)
- `reset()` 으로 사이클 재시작 시 인스턴스 재사용

**TradePhase** (`autotrade/dto/TradePhase.java`)
```
TRIGGER_WAIT    → 방향 판단 전 대기
POSITION_OPEN   → (deprecated, 즉시 BLOCK_MATCHING 전환)
BLOCK_MATCHING  → 블록 순차 관찰 중
POSITION_HOLDING → 포지션 보유 중 TP/SL 모니터링
```

---

### market — 시장 데이터

**MarketService** (`market/service/MarketService.java`)
- Bybit WebSocket 구독: 실시간 가격(ticker), 1분/5분/15분/30분/1시간 Kline
- `isWsActive()` 메서드로 WebSocket 연결 상태 확인
- Kline 봉 마감 시 콜백 실행 → AutoTradeService 신호 트리거
- `convertUsdtToQty(symbol, usdt)` — USDT 금액 → 코인 수량 변환 (qtyStep 기반)
- 거래량 기준 정렬 전체 종목 목록 제공

**BybitWebSocketClient** (`common/client/market/BybitWebSocketClient.java`)
- Bybit WebSocket 연결 관리 (ping 20초, 재연결 최대 30초)
- ticker/kline 구독 등록 및 콜백 디스패치

---

### trade — 주문 실행

**TradeFacade** (`trade/service/TradeFacade.java`)
- TradeMode 기반 라우팅: `MAIN → TradeService`, `SIM → SimTradeService`
- 레버리지 설정, 마진 모드 설정, 주문 실행 단일 진입점

**TradeService** (`trade/service/TradeService.java`)
- Bybit OpenFeign 클라이언트 직접 호출
- 시장가 주문(Market Order) 전용

---

### sim — 모의투자

**SimWalletService** (`sim/service/SimWalletService.java`)
- 사용자당 가상 지갑 1개 (초기값 10,000 USDT)
- 거래 체결 후 잔액 갱신

**SimTradeService** (`sim/service/SimTradeService.java`)
- 실제 Bybit API 호출 없이 모의 주문 처리
- 모의 거래/투자 히스토리 기록

---

### login — 인증

**LoginService** (`login/service/LoginService.java`)
- 휴대폰 번호 + 비밀번호 로그인 (BCrypt 검증)
- Bybit API 키 AES-256 암호화 저장

**BybitApiKeyValidator** (`login/service/BybitApiKeyValidator.java`)
- 계정 잔액 조회 API 호출로 API Key/Secret 유효성 검증

---

## 5. 핵심 엔티티 스키마

### PatternQueue (`pattern_queue`)
| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long PK | |
| userId | Long | 소유자 (FK 없음) |
| symbol | String | 코인 심볼 (예: BTCUSDT) |
| isActive | boolean | 활성화 여부 |
| activatedAt | LocalDateTime | 활성화 일시 |
| triggerRate | BigDecimal(5,2) | 트리거 기준 변동률 (%) |
| isFull | boolean | 모든 단계 가득 참 여부 |
| tradeMode | TradeMode | MAIN / SIM |
| cycle | boolean | 전 단계 소진 후 1단계 재시작 여부 |
| currentStepId | Long | 현재 진행 단계 ID (앱 레벨 관리) |
| currentPatternId | Long | 현재 진행 패턴 ID (앱 레벨 관리) |
| currentBlockOrder | Integer | 현재 대기 블록 순서 (앱 레벨 관리) |

### PatternStep (`pattern_step`)
| 필드 | 설명 |
|------|------|
| id, queueId | |
| stepLevel | 단계 번호 (1~20) |

### Pattern (`pattern`)
| 필드 | 설명 |
|------|------|
| id, step | 소속 단계 |
| leverage | 레버리지 배수 |
| amount | 주문 금액 USDT (마진) |
| stopLossRate | 손절 비율 % (null = 미설정) |
| takeProfitRate | 익절 비율 % (null = 미설정) |
| patternOrder | 단계 내 패턴 순서 |

### PatternBlock (`pattern_block`)
| 필드 | 설명 |
|------|------|
| id, pattern | 소속 패턴 |
| side | LONG / SHORT |
| blockOrder | 블록 실행 순서 |
| isLeaf | true = 실행(리프) 블록, false = 조건 블록 |

### TradeHistory (`trade_history`)
| 필드 | 설명 |
|------|------|
| id, userId, symbol | |
| side | LONG / SHORT |
| amount | 주문 금액 USDT |
| executedPrice | 체결가 |
| orderId | Bybit 주문 ID |
| fee | 수수료 (taker 0.055%) |
| orderType | ENTRY / SELL / LIQUIDATION |
| orderResult | SUCCESS / FAILED |
| errorMessage | 실패 시 오류 내용 |

### InvestmentHistory (`investment_history`)
| 필드 | 설명 |
|------|------|
| id, userId, symbol | |
| side | LONG / SHORT |
| profitLoss | 손익금 |
| entryPrice / exitPrice | 진입가 / 청산가 |
| amount | 투입 마진 (레버리지 미포함) |
| leverage | 레버리지 |
| tpPrice / slPrice | 익절/손절 예상가 |

> `SimTradeHistory`, `SimInvestmentHistory` 는 위 두 테이블과 동일 구조, `sim_` 접두 테이블에 저장.

### SimWallet (`sim_wallet`)
| 필드 | 설명 |
|------|------|
| id, userId | 사용자당 1개 |
| balance | 가상 잔액 (USDT, 초기 10,000) |

### User (`user`)
| 필드 | 설명 |
|------|------|
| id, phone | 휴대폰 번호 로그인 |
| password | BCrypt 해시 |
| apiKey / apiSecret | AES-256 암호화 저장 |

---

## 6. 자동매매 엔진 — 3-Phase 상세

```
큐 활성화
    │
    ▼
[TRIGGER_WAIT]
    현재가와 기준가(basePrice) 비교
    ±triggerRate% 도달 시 방향(LONG/SHORT) 결정
    → 1단계 1패턴 1블록으로 초기화
    │
    ▼ (방향 결정됨)
[BLOCK_MATCHING]
    현재 블록 관찰 (60초 대기, blockBaseTime 기준)
    ├── 조건 블록 (isLeaf=false):
    │       60초 후 가격이 블록 방향과 일치 → 다음 블록으로 진행
    │       60초 후 가격이 반대 방향 → 같은 단계 내 반대 패턴 전환 (없으면 TRIGGER_WAIT 리셋)
    │
    └── 리프 블록 (isLeaf=true):
            즉시 진입 (시장가 주문)
            → POSITION_HOLDING 전환
    │
    ▼ (진입 완료)
[POSITION_HOLDING]
    실시간 가격 vs tpPrice / slPrice 비교
    ├── 현재가 >= tpPrice(LONG) 또는 <= tpPrice(SHORT) → 매도 (SELL)
    └── 현재가 <= slPrice(LONG) 또는 >= slPrice(SHORT) → 청산 (LIQUIDATION)
    
    매도 성공 → 다음 단계 이동 (또는 cycle=true이면 1단계 재시작)
    청산 → 같은 단계 내 다음 패턴 (없으면 다음 단계)
```

### 진입 방향 결정 규칙
- **진입 포지션 방향 = leaf 블록의 side** (첫 번째 조건 블록의 side가 아님)
- 예: 조건 블록 LONG → leaf 블록 SHORT 이면 SHORT 포지션 진입

### 수량 계산
```
notional = pattern.amount × leverage   (USDT 기준 포지션 규모)
qty = notional / currentPrice
qty → qtyStep 단위로 반올림
qty = 0 이면 entrySkipCount 증가, 5회 초과 시 큐 비활성화
```

### TP/SL 예상가 계산
```
LONG:
  tpPrice = entryPrice × (1 + takeProfitRate / leverage / 100)
  slPrice = entryPrice × (1 - stopLossRate / leverage / 100)

SHORT:
  tpPrice = entryPrice × (1 - takeProfitRate / leverage / 100)
  slPrice = entryPrice × (1 + stopLossRate / leverage / 100)
```

### 손익 계산
```
LONG:  profitLoss = (exitPrice - entryPrice) × qty - fee
SHORT: profitLoss = (entryPrice - exitPrice) × qty - fee
fee = executedPrice × qty × TAKER_FEE_RATE (0.00055)
```

---

## 7. 실시간 통신 구조

### Bybit WebSocket (서버 ↔ Bybit)
- **URL**: `wss://stream.bybit.com/v5/public/linear`
- **ping 주기**: 20초 / **재연결**: 최대 30초
- **구독 토픽**:
  - `tickers.{symbol}` — 실시간 가격 (1초 갱신)
  - `kline.1.{symbol}` — 1분봉 (confirm:true 일 때 봉 마감)
  - `kline.5/15/30/60.{symbol}` — 다봉 단위

### STOMP WebSocket (서버 → 브라우저)
- **Config**: `WebSocketStompConfig`
- **Endpoint**: `/ws-stomp`
- **토픽**:
  - `/topic/autotrade.log.{symbol}` — 자동매매 실행 로그
  - `/topic/autotrade.alert.{symbol}` — 비활성화/에러 알림 (`AutoTradeAlertResponse`)
  - `/topic/price.{symbol}` — 실시간 시세

---

## 8. Bybit API 클라이언트 (OpenFeign)

| 클라이언트 | 역할 |
|-----------|------|
| `MarketClient` | 시세, Kline, 호가, 종목 정보 (공개 API) |
| `TradeClient` | 주문 생성 (인증 필요) |
| `AccountClient` | 지갑 잔액 (인증 필요) |
| `PositionClient` | 포지션 목록 (인증 필요) |

- 인증: `BybitFeignConfig` — HMAC-SHA256 서명 + API Key 헤더 삽입
- 사용자 API Key는 `UserApiKeyContext` (ThreadLocal)로 요청 스코프 전달

---

## 9. 테스트 전략

| 레이어 | 방식 |
|--------|------|
| Service | 단위 테스트 (Mock 사용) |
| Controller | 통합 테스트 |
| Repository | 통합 테스트 |
| Bybit API 모의 | WireMock (`test/resources/__files/` 디렉터리에 JSON 응답 파일 관리) |

- 테스트 DB: H2 인메모리

---

## 10. 설정 파일

### application.yml (주요 항목)
```yaml
server.port: 8080
spring.jpa.hibernate.ddl-auto: none          # DDL 자동 생성 안 함
spring.jpa.properties.hibernate.jdbc.time_zone: Asia/Seoul

bybit.api.url: https://api.bybit.com
bybit.api.recv-window: 5000
bybit.websocket.url: wss://stream.bybit.com/v5/public/linear
bybit.websocket.ping-interval: 20000
bybit.websocket.reconnect-delay: 1000
bybit.websocket.reconnect-max-delay: 30000
```

### application-local.yml (로컬 작성 필요, git 제외)
```yaml
spring.datasource.url: jdbc:mariadb://{host}:{port}/{db}
spring.datasource.username:
spring.datasource.password:
aes.encryption.key: {32바이트 키}
```

---

## 11. 개발 규칙 요약 (CLAUDE.local.md)

- git commit/push는 사용자가 직접 수행
- TDD: 실패하는 테스트 먼저 작성 후 구현
- API 응답 → Map 지양, 전용 Response 객체 생성
- 요청 객체: `~Request`, 응답 객체: `~Response`, 내부 객체: `~DTO`
- 주요 분기/파싱 로직에 주석 필수
- Service/Client에 JavaDoc 필수 (파라미터, 반환값 기술)
- Entity/DTO 필드에 인라인 주석 (`// 설명`)
- 프론트는 jQuery 적극 사용
- 세션 종료 시 `others/session_YYYY_MM_DD.md` 작성

---

## 12. 알려진 이슈 및 백로그

| 항목 | 상태 | 비고 |
|------|------|------|
| `amount` 필드 의미 혼용 | 주의 | `Pattern.amount`는 마진(USDT), `InvestmentHistory.amount`도 마진. notional은 `amount × leverage` |
| `close_type` 컬럼 추가 | 보류 | DB 마이그레이션 필요 (매도/청산 구분 표시) |
| SL/TP 안전 계수 | 부분 적용 | leverage 25 초과 시 강제청산 리스크 존재 |
| 이중 진입 방지 | Option A 적용 | entryPrice 선점 + 상단 가드 (재발 시 Option B 검토) |

---

*이 문서는 코드 변경 시 함께 갱신하도록 한다.*
