# Web Coin Trader

Bybit API 기반 암호화폐 자동매매 웹 애플리케이션

## 주요 기능

- **실시간 대시보드** - 전체 코인 시세 목록 (1초 갱신, 거래량 기준 정렬)
- **상세 차트** - 코인별 캔들 차트 (1분/5분/15분/30분/1시간) + 호가창
- **패턴 기반 자동매매** - 큐 > 단계 > 패턴 > 블록 계층 구조로 매매 전략 구성
- **마이페이지** - 거래 히스토리 조회, 패턴 관리
- **회원가입/로그인** - Bybit API Key 검증 포함

## 기술 스택

| 구분 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.4.2 |
| Frontend | Thymeleaf, ApexCharts, Vanilla JS |
| Database | MariaDB (개발/운영), H2 (테스트) |
| API 통신 | Spring Cloud OpenFeign |
| 실시간 | WebSocket (Bybit 시세 스트림) |
| 인증 | Spring Security |
| 테스트 | JUnit 5, WireMock |
| 빌드 | Gradle |

## 프로젝트 구조

```
src/main/java/com/coin/webcointrader/
├── autotrade/          # 자동매매 (큐, 패턴, 단계 관리 + 엔티티/리포지토리)
├── trade/              # 거래 실행 Facade, 실전 거래·투자 히스토리
├── sim/                # 모의투자 (가상 지갑, 모의 히스토리)
├── login/              # 로그인/회원가입, 거래소 API Key
├── mypage/             # 마이페이지 (타 도메인 Service/DTO 로만 접근)
├── market/             # 시장 데이터 (시세, 호가)
├── wallet/             # 실전 지갑 잔고
├── bybit/              # Bybit 연동 (Feign 클라이언트, WebSocket, 요청/응답 DTO)
└── common/             # 공통 (BaseEntity, Enum, 거래소 추상화, 설정, 유틸)

※ 각 도메인은 controller / service / repository / entity / dto 를 보유하며,
  다른 도메인의 Repository·Entity 를 직접 호출하지 않고 Service 와 DTO 를 통해 접근한다.

src/main/resources/
├── templates/          # Thymeleaf 템플릿 (대시보드, 차트, 마이페이지 등)
├── static/             # CSS, JS 정적 리소스
└── application.yml     # 애플리케이션 설정
```

## 실행 방법

### 사전 요구사항
- Java 17+
- MariaDB
- Bybit Testnet API Key/Secret

### 1. MariaDB 데이터베이스 준비
- MariaDB 서버 실행
- `coin_trader` 데이터베이스 생성
- DDL 스크립트는 별도 관리

### 2. 환경 설정
`src/main/resources/application-local.yml` 파일 생성:
```yaml
spring:
  datasource:
    url: jdbc:mariadb://{호스트}:{포트}/{DB명}
    username: {사용자명}
    password: {비밀번호}

aes:
  encryption:
    key: {AES 암호화 키 (32바이트)}
```

### 3. 빌드 및 실행
```bash
./gradlew build
./gradlew bootRun
```

### 4. 접속
- http://localhost:8080

## 테스트

```bash
./gradlew test
```
- Service: 단위 테스트
- Controller/Repository: 통합 테스트
- API 모의: WireMock 사용
