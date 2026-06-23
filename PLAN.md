# kyuloud-ai — Local LLM API 개발 계획

Spring AI 기반으로 로컬 LLM(Ollama)을 구동하는 API를 **Simple Chat → RAG → Agent Chat** 순서로 단계적으로 구축하기 위한 아키텍처 및 개발 계획 문서.

---

## 0. 현재 상태 (Baseline)

| 항목 | 내용 |
| --- | --- |
| 빌드 | Gradle 9.5.1, Java 17 |
| 프레임워크 | Spring Boot 4.1.0 |
| AI | Spring AI 2.0.0 (`spring-ai-starter-model-ollama`) |
| 기타 | Lombok, DevTools |
| 코드 | `KyuloudAiApplication` (빈 부트스트랩) + `application.yaml` (앱 이름만 설정) |

> 즉, 모델 연동·도메인 코드가 전혀 없는 깨끗한 스켈레톤 상태이므로 아래 계획대로 처음부터 구조를 잡아 나간다.

---

## 1. 최종 목표 아키텍처

### 1.1 전체 구성도

```
                         ┌─────────────────────────────────────────────┐
                         │                Client (Web / CLI)           │
                         └───────────────────────┬─────────────────────┘
                                                 │ HTTP / SSE(Stream)
                                                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                          kyuloud-ai (Spring Boot)                              │
│                                                                                │
│  ┌────────────┐   ┌──────────────────────────────────────────────────────┐   │
│  │ Controller │──▶│                  ChatClient (Spring AI)              │   │
│  │  Layer     │   │  ┌──────────────────────────────────────────────┐    │   │
│  │ (REST/SSE) │   │  │  Advisor Chain                               │    │   │
│  └────────────┘   │  │  - ChatMemoryAdvisor   (대화 맥락)           │    │   │
│        │          │  │  - QuestionAnswerAdvisor / RAG Advisor       │    │   │
│        │          │  │  - SafeGuard / Logging Advisor               │    │   │
│        │          │  └──────────────────────────────────────────────┘    │   │
│        │          │  ┌──────────────┐   ┌────────────────────────────┐    │   │
│        │          │  │ Tool Calling │   │  Prompt / SystemTemplate   │    │   │
│        │          │  │ (@Tool)      │   │                            │    │   │
│        │          │  └──────────────┘   └────────────────────────────┘    │   │
│        │          └───────┬───────────────────────────┬──────────────────┘   │
│        │                  │                            │                       │
│        ▼                  ▼                            ▼                       │
│  ┌────────────┐    ┌────────────┐            ┌──────────────────┐             │
│  │ ChatMemory │    │ Tools/MCP  │            │   RAG Pipeline   │             │
│  │ Repository │    │ Functions  │            │ (Ingest+Retrieve)│             │
│  └────────────┘    └────────────┘            └────────┬─────────┘             │
└──────────────────────────────────────────────────────┼───────────────────────┘
                                                        │
        ┌───────────────────────┬───────────────────────┼─────────────────────┐
        ▼                       ▼                        ▼                     ▼
┌──────────────┐      ┌──────────────────┐    ┌──────────────────┐  ┌────────────────┐
│   Ollama     │      │  Embedding Model │    │   Vector Store   │  │  RDB (메타/세션)│
│ (Chat LLM)   │      │  (Ollama embed)  │    │  (pgvector etc.) │  │  PostgreSQL    │
└──────────────┘      └──────────────────┘    └──────────────────┘  └────────────────┘
```

### 1.2 핵심 컴포넌트

- **ChatClient**: Spring AI의 fluent API. 모든 대화 진입점. Advisor 체인·툴·프롬프트를 조합한다.
- **Advisor Chain**: 요청/응답을 가로채는 미들웨어. 대화 메모리, RAG 검색 주입, 로깅/가드레일을 담당.
- **ChatMemory**: 세션별 대화 히스토리. 초기엔 인메모리, 이후 JDBC/RDB로 영속화.
- **RAG Pipeline**: 문서 적재(ETL) + 검색(Retrieval). VectorStore + Embedding Model 사용.
- **Tool Calling**: `@Tool` 어노테이션 기반 함수 호출. Agent 단계의 핵심.
- **Ollama**: 로컬에서 구동되는 Chat / Embedding 모델 백엔드.

---

## 2. Agent 구조

### 2.1 단계별 진화

```
[1단계] Simple Chat
   Client ─▶ ChatClient ─▶ Ollama ─▶ 응답
   (옵션) + ChatMemoryAdvisor → 멀티턴 대화

[2단계] RAG Chat
   Client ─▶ ChatClient
              └─ QuestionAnswerAdvisor / RetrievalAugmentationAdvisor
                   ├─ 질의 임베딩 → VectorStore 유사도 검색
                   └─ 검색된 context를 프롬프트에 주입 ─▶ Ollama ─▶ 근거 기반 응답

[3단계] Agent Chat (Tool / ReAct 루프)
   Client ─▶ ChatClient (+ Tools + RAG + Memory)
              ├─ LLM이 도구 호출 필요성 판단
              ├─ Tool 실행 (검색/계산/외부API/RAG검색 등)
              ├─ 결과를 다시 LLM에 전달 (관찰→추론→행동 반복)
              └─ 최종 답변 생성
```

### 2.2 Agent 내부 구조 (3단계 목표)

```
                    ┌────────────────────────────┐
                    │        AgentService        │
                    │  (오케스트레이션 진입점)    │
                    └─────────────┬──────────────┘
                                  │
                ┌─────────────────┼──────────────────┐
                ▼                 ▼                  ▼
       ┌────────────────┐ ┌──────────────┐  ┌──────────────────┐
       │  Planner       │ │  ToolRegistry│  │  Memory Manager  │
       │ (의도/계획)    │ │  (@Tool 집합)│  │ (단기/장기 기억) │
       └───────┬────────┘ └──────┬───────┘  └─────────┬────────┘
               │                 │                    │
               ▼                 ▼                    ▼
       ┌──────────────────────────────────────────────────────┐
       │              ChatClient (LLM 추론 엔진)               │
       │           ReAct: 추론 → 도구호출 → 관찰 → 반복        │
       └──────────────────────────────────────────────────────┘
                                  │
            ┌─────────────────────┼─────────────────────┐
            ▼                     ▼                     ▼
   ┌────────────────┐   ┌──────────────────┐  ┌──────────────────┐
   │  RAG Tool      │   │  Web/External    │  │  Domain Tool     │
   │ (문서 검색)    │   │  API Tool        │  │ (DB/계산/업무)   │
   └────────────────┘   └──────────────────┘  └──────────────────┘
```

- **Planner**: 사용자 요청을 분석해 어떤 도구/RAG가 필요한지 판단(초기엔 LLM 자체 tool-calling에 위임, 고도화 시 명시적 plan 단계 추가).
- **ToolRegistry**: `@Tool`로 등록된 함수들의 집합. RAG 검색도 하나의 Tool로 노출 가능.
- **Memory Manager**: 단기(현재 세션) + 장기(VectorStore 기반 과거 대화 회상) 기억 관리.
- **ReAct 루프**: Spring AI의 tool-calling이 자동으로 처리 (LLM이 tool 호출 → Spring AI가 실행 → 결과 재주입 반복).

---

## 3. 프로젝트 구조

패키지 루트: `com.kyuloud.ai`

```
src/main/java/com/kyuloud/ai/
├── KyuloudAiApplication.java
│
├── config/                          # 설정·빈 정의
│   ├── ChatClientConfig.java        # ChatClient.Builder, 공통 Advisor 구성
│   ├── OllamaConfig.java            # Chat/Embedding 모델 옵션
│   ├── VectorStoreConfig.java       # VectorStore 빈 (pgvector 등)
│   └── CorsConfig.java
│
├── common/                          # 횡단 관심사
│   ├── dto/                         # 공통 응답 래퍼, 에러 DTO
│   ├── exception/                   # 예외 + GlobalExceptionHandler
│   └── advisor/                     # 커스텀 Advisor (로깅/가드레일)
│
├── chat/                            # [1단계] Simple Chat
│   ├── controller/ChatController.java       # POST /api/chat, /api/chat/stream
│   ├── service/ChatService.java
│   ├── memory/ChatMemoryConfig.java         # ChatMemory 빈 (InMemory→JDBC)
│   └── dto/ChatRequest.java, ChatResponse.java
│
├── rag/                             # [2단계] RAG
│   ├── controller/
│   │   ├── DocumentController.java          # 문서 업로드/적재 API
│   │   └── RagChatController.java           # RAG 기반 질의 API
│   ├── ingest/                              # ETL (적재) 파이프라인
│   │   ├── DocumentIngestService.java
│   │   ├── reader/ (PDF/Text/Markdown Reader)
│   │   └── splitter/ (TokenTextSplitter 설정)
│   ├── retrieval/RagRetrievalService.java   # 검색 + Advisor 구성
│   └── dto/
│
├── agent/                           # [3단계] Agent Chat
│   ├── controller/AgentController.java      # POST /api/agent/chat
│   ├── service/AgentService.java            # 오케스트레이션
│   ├── tool/                                # @Tool 정의
│   │   ├── RagSearchTool.java
│   │   ├── WebSearchTool.java
│   │   ├── DateTimeTool.java
│   │   └── DomainTool.java
│   ├── planner/ (선택) PlannerService.java
│   └── dto/
│
└── domain/                          # 영속 엔티티 (세션/메시지/문서 메타)
    ├── entity/
    └── repository/

src/main/resources/
├── application.yaml                 # 공통 설정
├── application-local.yaml           # 로컬 프로파일 (Ollama 주소 등)
├── prompts/                         # 시스템 프롬프트 템플릿 (.st)
│   ├── system-chat.st
│   ├── system-rag.st
│   └── system-agent.st
└── db/migration/                    # Flyway (선택)
```

> 패키지를 **기능(feature) 기준**으로 나눠 단계별로 독립 확장 가능하게 구성한다. `chat` → `rag` → `agent`가 점진적으로 앞 단계를 재사용한다(agent가 rag·memory를 도구/Advisor로 흡수).

---

## 4. 기술 스택 / 의존성 추가 계획

| 단계 | 추가 의존성 | 용도 |
| --- | --- | --- |
| 공통(현재) | `spring-ai-starter-model-ollama` | Chat/Embedding 모델 |
| 공통 | `spring-boot-starter-web` | REST API (현재 누락 → **추가 필요**) |
| 공통 | `spring-boot-starter-validation` | 요청 검증 |
| 1단계 | `spring-ai-starter-model-chat-memory` (또는 JDBC memory) | 대화 메모리 영속화 |
| 2단계 | `spring-ai-starter-vector-store-pgvector` | 벡터 저장소 |
| 2단계 | `spring-boot-starter-data-jpa`, `postgresql` | 메타데이터/세션 RDB |
| 2단계 | `spring-ai-*-document-reader` (tika/pdf) | 문서 파싱 |
| 3단계 | (옵션) `spring-ai-starter-mcp-client` | 외부 MCP 도구 연동 |

> **즉시 확인 필요**: 현재 `build.gradle`에 `spring-boot-starter-web`이 없어 REST 컨트롤러가 동작하지 않는다. 1단계 착수 시 가장 먼저 추가한다.

### Ollama 사전 준비
```bash
# 로컬에 Ollama 설치 후
ollama pull llama3.1          # 또는 qwen2.5, gemma2 등 Chat 모델
ollama pull nomic-embed-text  # 임베딩 모델 (RAG 단계)
```

`application.yaml` 예시(목표):
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.1
          temperature: 0.7
      embedding:
        options:
          model: nomic-embed-text
```

---

## 5. 개발 순서 (Phase별 상세)

### Phase 0 — 프로젝트 기반 정비 ✅ 완료
1. `build.gradle`에 `spring-boot-starter-web`, `validation`, `actuator` 추가.
2. 프로파일 분리(`application.yaml` / `application-local.yaml`), Ollama base-url + 모델(`gemma4:e4b`) 설정.
3. 공통 응답 래퍼(`ApiResponse`), `BusinessException`, `GlobalExceptionHandler` 작성.
4. 동작 확인용 `PingController`(`GET /api/ping`) 작성.
- **완료 기준**: `compileJava` 성공. (앱 부팅/HTTP 검증은 추후 수행, Ollama 모델 pull은 Phase 1에서 필요.)

### Phase 1 — Simple Chat
소스 수정 범위가 넓어 하위 단계로 나누어 각 단계마다 컴파일/동작을 검증한다.

#### Phase 1a — 기본 단발 채팅 ✅ 완료
1. `ChatClientConfig`에서 `ChatClient.Builder`로 기본 `ChatClient` 빈 생성 + 시스템 프롬프트 지정.
2. `chat` 패키지: `ChatController` + `ChatService` + `ChatRequest`/`ChatResponse` DTO.
   - `POST /api/chat` : 단발 질의/응답.
- **완료 기준**: 단발 질의에 LLM 응답 반환(`gemma4:e4b`). ✅ 실호출 검증 완료.

#### Phase 1b — 스트리밍 응답 ✅ 완료
1. `ChatService`에 스트리밍 메서드 추가(`Flux<String>` / `ChatClient.stream()`).
2. `POST /api/chat/stream` : SSE(`text/event-stream`) 응답.
3. (개선) `GlobalExceptionHandler`에 `HttpMessageNotReadableException` → `400 MALFORMED_REQUEST` 추가.
- **완료 기준**: 토큰 단위 스트리밍 응답 수신. ✅ SSE 스트리밍 + 400 응답 검증 완료.

#### Phase 1c — 멀티턴 메모리 ✅ 완료
1. `ChatMemory`(InMemory, `MessageWindowChatMemory`, max 20) 빈 + `MessageChatMemoryAdvisor`를 `ChatClient` 기본 advisor로 적용.
2. `ChatRequest`에 `conversationId`(선택) 추가, 서비스에서 메모리 파라미터로 전달(없으면 `default`).
- **완료 기준**: 세션 ID 기준으로 이전 대화를 기억하며 응답. ✅ 동일 세션 기억 + 세션 간 격리 검증 완료.

#### Phase 1d — 프롬프트 템플릿 분리 ✅ 완료
1. 시스템 프롬프트를 `resources/prompts/system-chat.st`로 분리, `ChatClientConfig`에서 `@Value` Resource로 주입(`defaultSystem(Resource)`).
- **완료 기준**: 프롬프트 외부화, 코드 변경 없이 프롬프트 수정 가능. ✅ 정체성 + "모르면 모른다" 규칙 반영 검증 완료.

### Phase 2 — RAG (3~4일)
1. **인프라**: PostgreSQL + pgvector 기동(Docker), `VectorStoreConfig` 빈 구성, 임베딩 모델 설정.
2. **Ingest(ETL) 파이프라인**:
   - `DocumentController`로 파일 업로드(`POST /api/documents`).
   - `DocumentReader`(Tika/PDF) → `TokenTextSplitter`로 청킹 → `VectorStore.add()`로 임베딩 적재.
   - 문서 메타데이터(원본명, chunk 수 등)는 RDB에 저장.
3. **Retrieval**:
   - `QuestionAnswerAdvisor`(기본) 또는 `RetrievalAugmentationAdvisor`(고급: query 변환·재정렬)로 RAG 구성.
   - `POST /api/rag/chat` : 검색 context 주입 후 응답 + 출처(citation) 반환.
4. 검색 파라미터(topK, similarityThreshold) 튜닝 및 프롬프트 템플릿(`system-rag.st`) 정비.
- **완료 기준**: 업로드한 문서 내용에 근거해 답변하고, 모르면 "모른다"고 응답(환각 억제).

### Phase 3 — Agent Chat (4~6일)
1. **Tool 기본**: `@Tool` 어노테이션으로 단순 도구(DateTimeTool 등) 등록 → tool-calling 동작 검증.
2. **RAG를 Tool로 노출**: `RagSearchTool` — Agent가 필요 시 능동적으로 문서 검색.
3. **외부/도메인 Tool 추가**: WebSearch, DB 조회, 업무 API 등.
4. `AgentService`로 오케스트레이션: Memory + RAG + Tools를 단일 `ChatClient`에 결합한 ReAct 루프.
5. (선택) **MCP Client** 연동으로 외부 표준 도구 확장.
6. (선택) **Planner** 단계 분리 — 복잡한 multi-step 작업의 계획 수립.
- **완료 기준**: "오늘 날짜 기준으로 X 문서 요약해줘" 같은 도구+RAG 복합 질의를 자율적으로 수행.

### Phase 4 — 운영·품질 강화 (지속)
- 가드레일 Advisor(PII/금칙어), 요청·응답 로깅/추적(observability).
- 토큰·지연 메트릭(Micrometer), Rate limiting.
- 대화 메모리 JDBC 영속화로 전환, 세션 만료 정책.
- 테스트: 단위 + `@SpringBootTest` 통합 + 평가(RAG 정확도) 시나리오.

---

## 6. API 엔드포인트 요약 (목표)

| 단계 | Method | Path | 설명 |
| --- | --- | --- | --- |
| 1 | POST | `/api/chat` | 단발 채팅 |
| 1 | POST | `/api/chat/stream` | 스트리밍 채팅 (SSE) |
| 2 | POST | `/api/documents` | 문서 업로드/적재 |
| 2 | GET | `/api/documents` | 적재 문서 목록 |
| 2 | POST | `/api/rag/chat` | RAG 기반 질의 |
| 3 | POST | `/api/agent/chat` | Agent(도구+RAG+메모리) 채팅 |

공통: 요청에 `conversationId`(세션 식별자)를 포함해 메모리/맥락을 관리한다.

---

## 7. 마일스톤 체크리스트

- [x] **M0** web/validation 의존성 추가, 프로파일·예외 처리, ping 엔드포인트 (✅ Phase 0)
- [x] **M1** Simple Chat (스트리밍 + 멀티턴 메모리 + 프롬프트 외부화) (✅ Phase 1)
- [ ] **M2** RAG (문서 적재 + 검색 + 출처 응답)
- [ ] **M3** Agent (tool-calling + RAG tool + ReAct 루프)
- [ ] **M4** 운영 강화 (가드레일/관측/영속화/테스트)

---

## 8. 주요 설계 원칙

1. **점진적 확장**: 각 단계가 이전 단계를 재사용. Advisor·Tool로 기능을 "끼워넣는" 방식.
2. **모델 교체 용이성**: Ollama 옵션을 설정으로 분리해 모델(llama/qwen/gemma) 교체를 쉽게.
3. **로컬 우선**: 외부 클라우드 의존 없이 로컬에서 완결되도록 구성(데이터 프라이버시).
4. **관측 가능성**: 초기부터 로깅 Advisor를 넣어 프롬프트/토큰/지연을 추적.
5. **환각 억제**: RAG 단계부터 출처 명시 + "근거 없으면 모른다" 프롬프트 전략.
