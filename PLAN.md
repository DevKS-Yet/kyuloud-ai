# kyuloud-ai — Local LLM API 개발 계획

Spring AI 기반으로 로컬 LLM(Ollama)을 구동하는 API를 **Simple Chat → RAG → Agent Chat** 순서로 단계적으로 구축하기 위한 아키텍처 및 개발 계획 문서.

---

## 0. 현재 상태 (Baseline)

| 항목 | 내용 |
| --- | --- |
| 빌드 | Gradle 9.5.1, Java 21 (가상 스레드 활성화: `spring.threads.virtual.enabled=true`) |
| 프레임워크 | Spring Boot 4.1.0 |
| AI | Spring AI 2.0.0 (`spring-ai-starter-model-ollama`) |
| API 문서 | springdoc-openapi 3.0.3 (Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`) |
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
| 공통 | `springdoc-openapi-starter-webmvc-ui` | Swagger UI / OpenAPI 문서 (통합 테스트용) ✅ 추가 완료 |
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

### Phase 2 — RAG
> **전략**: 먼저 **인메모리 `SimpleVectorStore`로 PoC(2a)** 를 끝내 RAG 동작을 검증하고,
> 이후 pgvector + RDB 영속화(2b)로 확장한다. (PoC 단계는 외부 인프라 없이 Ollama 임베딩 모델만 필요)

#### Phase 2a — 인메모리 RAG PoC ✅ 완료
1. **임베딩/벡터스토어**: `VectorStoreConfig`에서 `SimpleVectorStore`(Ollama `nomic-embed-text` 임베딩) 빈 구성.
2. **Ingest**: `POST /api/documents` (텍스트 본문 적재) → `TokenTextSplitter` 청킹 → `VectorStore.add()`.
3. **Retrieval**: `POST /api/rag/chat` — `QuestionAnswerAdvisor`로 검색 context 주입 후 응답.
4. **의존성 추가**: `spring-ai-vector-store`, `spring-ai-vector-store-advisor`.
- **완료 기준**: 적재한 문서 내용에 근거해 답변하고, 근거 없으면 "모른다"고 응답(환각 억제). ✅ 적재 사실 정답 + 없는 사실 "모른다" 검증 완료.
- **사전 준비**: `ollama pull nomic-embed-text` (완료).

#### Phase 2b — 영속화/확장 🚧 코드 구현 완료 · 검증 대기
1. **인프라**: PostgreSQL + pgvector 기동(`docker-compose.yml`, `pgvector/pgvector:pg16`). `VectorStore`를 pgvector 자동 구성으로 교체(`spring-ai-starter-vector-store-pgvector`, `initialize-schema`/HNSW/COSINE/dim 768). 인메모리 `SimpleVectorStore`는 `poc` 프로파일 폴백으로 보존.
2. **Ingest 확장**: 파일 업로드(`POST /api/documents` multipart + `TikaDocumentReader`), 문서 메타데이터 RDB 저장(`DocumentMetadata` 엔티티/리포지토리), 목록 조회(`GET /api/documents`).
3. **Retrieval 고도화**: `RetrievalAugmentationAdvisor` + `RewriteQueryTransformer`(query 재작성) + `VectorStoreDocumentRetriever`, 출처(citation) 반환(`RagChatResponse`).
4. 검색 파라미터(topK, similarityThreshold)를 `kyuloud.rag.*`로 외부화(`RagProperties`), 프롬프트 템플릿(`system-rag.st`) 추가.
- **검증 진행**: ① `docker compose up -d`로 pgvector 기동 ✅, ② `compileJava`/부팅 ✅ (앱 정상 실행 확인), ③ 파일 적재→`/api/rag/chat` 출처 포함 응답 ⬜, ④ 근거 없는 질의 "모른다" 응답 ⬜. (③·④ 기능 검증은 사용자가 Swagger UI로 직접 진행 중)
- **참고**: JPA·pgvector 도입으로 기본 프로파일 부팅에 PostgreSQL이 필요(컨텍스트 로드 테스트 포함). 오프라인 검증 시 `poc` 프로파일은 벡터스토어만 인메모리이며 메타데이터 저장은 여전히 DB 필요.

### Phase 3 — Agent Chat
> **전략**: Phase 1·2에서 만든 `ChatClient`·`ChatMemory`·`VectorStore`를 그대로 재사용하고 그 위에 **tool-calling**을 얹는다. Spring AI 2.0.0의 ReAct 루프(추론→도구호출→관찰→반복)는 `ToolCallingManager`가 자동 처리하므로 수동 루프 구현은 불필요. 도구를 하나씩 추가하며 단계(a~d)마다 컴파일·동작을 검증한다.

> **⚠️ 사전 확인 (중요)**: Ollama tool-calling은 **모델이 도구 호출을 지원해야** 동작한다. 현재 chat 모델 `gemma4:e4b`의 tool 지원 여부를 Phase 3a 착수 전 먼저 확인하고, 미지원이면 Agent 전용으로 tool-capable 모델(`qwen2.5`, `llama3.1` 등)을 별도 옵션/`ChatClient`로 구성한다.

**설계 핵심 (검증된 Spring AI 2.0.0 API 기준)**
- **도구 정의**: `org.springframework.ai.tool.annotation.@Tool` / `@ToolParam` 으로 메서드 도구 작성 → `chatClient.prompt().tools(toolBean)` 로 **요청별** 주입. 전역 `ChatClient`에 `defaultTools`를 걸면 chat/rag 경로까지 영향을 받으므로, Agent 경로에서만 per-request `.tools(...)` 로 주입한다.
- **메모리·프롬프트 재사용**: `AgentService`는 기존 `chatClient`(Memory advisor 포함)를 주입받아 `.system(system-agent.st)` 로 시스템 프롬프트만 오버라이드(`RagChatService`와 동일 패턴).
- **RAG 노출 방식 전환**: Phase 2b의 RAG는 advisor가 자동 주입했지만, Agent에서는 **하나의 Tool(`RagSearchTool`)** 로 노출해 LLM이 *필요하다고 판단할 때만* 능동 검색하게 한다.
- **(선택) toolContext**: `.toolContext(Map)` 로 `conversationId` 등을 도구에 전달해 컨텍스트 인지형 도구 구현 가능.

#### Phase 3a — Tool 기반 PoC (DateTimeTool) ✅ 완료
1. `agent` 패키지 생성: `controller/AgentController`(`POST /api/agent/chat`), `service/AgentService`, `dto/AgentResponse`. (입력은 `RagChatController` 선례를 따라 기존 `ChatRequest` 재사용 → 중복 방지)
2. `tool/DateTimeTool` — `@Tool` 로 현재 날짜/시각 반환(외부 의존 없음, tool-calling 동작 검증 전용).
3. `prompts/system-agent.st` — 도구 사용 지침 + "도구로 해결 안 되면 모른다" 규칙.
4. `AgentService`: `chatClient.prompt().system(agentPrompt).user(msg).tools(dateTimeTool).advisors(a→memory cid).call()`.
- **완료 기준**: "지금 몇 시야?" / "오늘 무슨 요일이야?" → LLM이 `DateTimeTool`을 호출해 실제 시각 기반으로 응답.
- **검증 완료** ✅: `compileJava` + 타 PC 통합테스트에서 도구 호출 동작 확인.

#### Phase 3b — RAG를 Tool로 노출 (RagSearchTool) ✅ 완료
1. `tool/RagSearchTool` — `@Tool`/`@ToolParam`: query를 받아 `vectorStore.similaritySearch`(topK/threshold = `RagProperties`) → 검색 청크 + 출처(source)를 번호 매긴 문자열로 반환.
2. `AgentService`에 `RagSearchTool` 추가 등록(`.tools(dateTimeTool, ragSearchTool)`), `system-agent.st`에 문서 검색 규칙 추가.
- **완료 기준**: 적재 문서 관련 질문 시 Agent가 **스스로** `RagSearchTool`을 호출해 근거 기반 답변(2b의 advisor 자동 주입과 달리 호출 여부를 LLM이 판단).
- **검증 완료** ✅: 타 PC 통합테스트에서 Agent가 `RagSearchTool`을 호출해 근거 기반 답변 확인.

#### Phase 3c — 멀티 툴 오케스트레이션 + 복합 질의 ✅ 완료
1. `tool/DocumentCatalogTool` — `DocumentMetadataRepository`로 적재 문서 목록/메타 조회.
2. `AgentService`에서 DateTime + RagSearch + DocumentCatalog + Memory를 단일 `ChatClient`에 결합(`.tools(...)`).
3. `AgentResponse.toolsUsed`로 호출된 도구 추적(tool-call trace) 노출 — `@RequestScope` `ToolCallTracker`에 각 도구가 호출 시 자기 이름 기록(요청 간 격리, 싱글톤 도구엔 스코프 프록시 주입).
- **완료 기준**: "오늘 날짜 기준으로 X 문서 요약해줘" 같은 도구+RAG 복합 질의를 자율적으로 수행.
- **검증 완료** ✅: 타 PC 통합테스트에서 복합 질의 자율 수행 + 응답 `toolsUsed`로 호출 도구 확인.

#### Phase 3d — 확장 (선택)
> **진행 방식**: 4개 항목은 실패 도메인(스트리밍 복잡도 / 외부 API / 외부 MCP 서버 / 아키텍처)이 서로 독립적이므로, 다른 단계와 동일하게 **위험·의존성 오름차순으로 세분화**해 각 단계마다 compile + 통합테스트로 격리 검증한다. 모두 선택 항목이라 취사선택·중단이 가능하다.

##### Phase 3d-1 — 스트리밍 tool-calling 🚧 코드 구현 완료 · 검증 대기
- `POST /api/agent/chat/stream` — `AgentService.stream()`(`ChatClient.stream().content()`)으로 도구 호출 + 최종 답변 SSE 스트리밍.
- **`ToolCallTracker` 보강**: `@RequestScope` → ThreadLocal 싱글톤으로 전환. 스트리밍 시 도구 실행이 reactor 스레드(요청 스레드 밖)에서 일어나도 `record()` 가 예외 없이 graceful degrade. blocking 경로는 동일 스레드라 추적 정확(응답 후 `reset()` 으로 누수 방지). 스트리밍 응답엔 `toolsUsed` 미포함.
- **완료 기준**: Agent가 도구를 호출하면서도 응답을 SSE로 스트리밍. **검증 진행**: `compileJava` ✅, 실호출 검증 ⬜.

##### Phase 3d-2 — WebSearchTool ⬜ (외부 검색 API)
- `tool/WebSearchTool` — 외부 검색 API(키 필요) 연동 `@Tool`. `ToolCallTracker` 기록 포함.
- **사전 준비**: 검색 제공자 선정 + API 키. **완료 기준**: 최신 정보 질의 시 Agent가 웹 검색 도구를 호출해 답변.

##### Phase 3d-3 — MCP Client ⬜ (외부 표준 도구)
- `spring-ai-starter-mcp-client` 의존성 추가, 외부 MCP 서버료 연동으로 표준 도구 확장.
- **사전 준비**: 연동할 MCP 서버. **완료 기준**: MCP 서버가 노출한 도구를 Agent가 호출.

##### Phase 3d-4 — Planner ⬜ (선택, 아키텍처 확장)
- 복잡한 multi-step 작업의 명시적 계획 수립 단계(`PlannerService`) 분리. 실제 필요성이 확인된 뒤 착수.
- **완료 기준**: 다단계 작업을 계획→실행으로 분리 수행.

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
| 3 | POST | `/api/agent/chat/stream` | Agent 스트리밍 채팅 (SSE, 3d-1) |

공통: 요청에 `conversationId`(세션 식별자)를 포함해 메모리/맥락을 관리한다.

---

## 7. 마일스톤 체크리스트

- [x] **M0** web/validation 의존성 추가, 프로파일·예외 처리, ping 엔드포인트 (✅ Phase 0)
- [x] **M1** Simple Chat (스트리밍 + 멀티턴 메모리 + 프롬프트 외부화) (✅ Phase 1)
- [ ] **M2** RAG (문서 적재 + 검색 + 출처 응답)
- [x] **M3** Agent (tool-calling + RAG tool + ReAct 루프) (✅ Phase 3a~3c, 통합테스트 정상)
- [ ] **M4** 운영 강화 (가드레일/관측/영속화/테스트)

---

## 8. 주요 설계 원칙

1. **점진적 확장**: 각 단계가 이전 단계를 재사용. Advisor·Tool로 기능을 "끼워넣는" 방식.
2. **모델 교체 용이성**: Ollama 옵션을 설정으로 분리해 모델(llama/qwen/gemma) 교체를 쉽게.
3. **로컬 우선**: 외부 클라우드 의존 없이 로컬에서 완결되도록 구성(데이터 프라이버시).
4. **관측 가능성**: 초기부터 로깅 Advisor를 넣어 프롬프트/토큰/지연을 추적.
5. **환각 억제**: RAG 단계부터 출처 명시 + "근거 없으면 모른다" 프롬프트 전략.
