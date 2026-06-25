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

#### Phase 2b — 영속화/확장 ✅ 완료
1. **인프라**: PostgreSQL + pgvector 기동(`docker-compose.yml`, `pgvector/pgvector:pg16`). `VectorStore`를 pgvector 자동 구성으로 교체(`spring-ai-starter-vector-store-pgvector`, `initialize-schema`/HNSW/COSINE/dim 768). 인메모리 `SimpleVectorStore`는 `poc` 프로파일 폴백으로 보존.
2. **Ingest 확장**: 파일 업로드(`POST /api/documents` multipart + `TikaDocumentReader`), 문서 메타데이터 RDB 저장(`DocumentMetadata` 엔티티/리포지토리), 목록 조회(`GET /api/documents`).
3. **Retrieval 고도화**: `RetrievalAugmentationAdvisor` + `RewriteQueryTransformer`(query 재작성) + `VectorStoreDocumentRetriever`, 출처(citation) 반환(`RagChatResponse`).
4. 검색 파라미터(topK, similarityThreshold)를 `kyuloud.rag.*`로 외부화(`RagProperties`), 프롬프트 템플릿(`system-rag.st`) 추가.
- **검증 완료** ✅: ① `docker compose up -d`로 pgvector 기동, ② `compileJava`/부팅, ③ 파일 적재→`/api/rag/chat` 출처 포함 응답, ④ 근거 없는 질의 "모른다" 응답.
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

##### Phase 3d-1 — 스트리밍 tool-calling ✅ 완료
- `POST /api/agent/chat/stream` — `AgentService.stream()`(`ChatClient.stream().content()`)으로 도구 호출 + 최종 답변 SSE 스트리밍.
- **`ToolCallTracker` 보강**: `@RequestScope` → ThreadLocal 싱글톤으로 전환. 스트리밍 시 도구 실행이 reactor 스레드(요청 스레드 밖)에서 일어나도 `record()` 가 예외 없이 graceful degrade. blocking 경로는 동일 스레드라 추적 정확(응답 후 `reset()` 으로 누수 방지). 스트리밍 응답엔 `toolsUsed` 미포함.
- **완료 기준**: Agent가 도구를 호출하면서도 응답을 SSE로 스트리밍. **검증 완료** ✅: `compileJava` + 실호출 검증 완료.

##### Phase 3d-2 — WebSearchTool ✅ 완료
- **방식**: SearXNG 자체 호스팅(API 키·요금 없음, 로컬 완결). `docker-compose.yml`에 `searxng/searxng:latest` 추가(포트 8888), `searxng/settings.yml`로 JSON 응답·엔진 구성.
- `tool/WebSearchTool` — `RestClient`로 SearXNG `/search?format=json` 호출 → 제목·URL·요약을 번호 매긴 문자열로 반환. SearXNG 미기동 시 예외 대신 안내 메시지 반환(graceful degrade).
- `config/SearchProperties` — `kyuloud.search.searxng-url`·`max-results` 외부화.
- `AgentService`에 `WebSearchTool` 추가(`.tools(..., webSearchTool)`). `system-agent.st`에 웹 검색 규칙 추가.
- **사전 준비**: `docker compose up -d`(SearXNG 기동). **완료 기준**: 최신 정보 질의 시 Agent가 `searchWeb` 도구를 호출해 답변, `toolsUsed`에 `"searchWeb"` 기록.
- **검증 완료** ✅: `compileJava` + 실호출 검증 완료.

##### Phase 3d-3 — MCP Client 🚧 코드 구현 완료 · 검증 대기
- **의존성**: `spring-ai-starter-mcp-client` 추가 (Spring MVC 호환 httpclient 기반, BOM 2.0.0 포함).
- **대상**: IntelliJ IDEA 내장 MCP 서버 (Settings → Tools → MCP Server → Enable). 50+ 도구(파일·검색·디버거·DB·터미널·리팩토링 등) 노출.
- **설정**: `spring.ai.mcp.client.sse.connections.intellij.url/sse-endpoint` — 포트는 동적 할당이므로 IntelliJ **Copy SSE Config** 에서 확인 후 `application-local.yaml` 기입. IntelliJ 미실행 시 `enabled: false` 로 비활성화.
- **AgentService**: `ObjectProvider<SyncMcpToolCallbackProvider>` 로 optional 주입(IntelliJ 꺼져도 앱 기동). MCP 연결 시 `provider.getToolCallbacks()` → `.tools((Object[])...)` 로 전달 — `toolCallbacks()` deprecated API 우회, `tools(Object...)` 비-deprecated 경로 사용.
- **완료 기준**: "현재 프로젝트 파일 목록 보여줘" 등 IntelliJ 도구를 Agent가 호출해 IDE 정보 기반 답변.
- **검증 진행**: `compileJava` (경고 없음) ✅. 실호출 검증 ⬜ (IntelliJ MCP Server 활성화 필요).

##### Phase 3d-4 — Planner ✅ 완료 (선택, 아키텍처 확장)
- **방식**: Plan-and-Execute. 복잡한 multi-step 요청을 `PlannerService` 가 순서가 있는 단계(`Plan`/`PlanStep`)로 분해(구조화 출력) → `AgentService.planAndExecute` 가 각 단계를 도구 탑재 `chatClient` 로 순차 실행 → `PlannerService.synthesize` 로 결과 합성.
- **대화 맥락**: 단계 실행은 임시 `planCid` 에서 수행하되, 시작 시 사용자 실제 대화 기록(`chatMemory.get(cid)`)을 `planCid` 에 시드해 단계들이 이전 맥락(예: 사용자 이름)을 인지한다(앞 단계 결과도 `planCid` 로 공유). 종료 시 중간 잡음은 `ChatMemory.clear(planCid)` 로 버리고, 사용자 대화(`cid`)에는 **원 질문 → 최종 답변** 한 턴만 기록해 다음 대화로 이어지게 한다. 즉 plan 도 `chat` 처럼 대화 맥락을 읽고 이어가되 내부 단계로 메모리를 오염시키지 않는다. 단일 단계면 합성 LLM 호출 생략.
- **전용 ChatClient**: 계획·합성은 도구·메모리 없는 `plannerChatClient` 빈으로 수행(주 `chatClient` 는 `@Primary`). 프롬프트는 `system-planner.st`·`system-synthesis.st` 로 외부화.
- **API**: `POST /api/agent/plan` → `PlanResponse`(계획·단계별 결과·최종 답변·`toolsUsed`).
- **완료 기준**: 다단계 작업을 계획→실행→합성으로 분리 수행. **검증 완료** ✅: `compileJava` + 실호출 검증 완료.

### Phase 4 — 운영·품질 강화 (지속)
> **진행 방식**: 4개 워크스트림(관측성 / 가드레일 / 메모리 영속화 / 테스트)은 서로 독립적이므로, 다른 Phase 와 동일하게 **위험·의존성 오름차순(4a~4d)으로 세분화**해 각 단계마다 compile + 검증으로 격리 진행한다.

#### Phase 4a — 관측 로깅 Advisor 🚧 코드 구현 완료 · 검증 대기
- `common/advisor/LoggingAdvisor` — `BaseAdvisor`(call+stream) 구현. 모든 `ChatClient` 호출을 가장 바깥에서 감싸 **요청(사용자 메시지) + 응답(답변·토큰 사용량·지연(ms))** 을 로깅. 지연은 `before`에서 시작 시각을 요청 컨텍스트에 담아 `after`에서 측정. order = `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100`(메모리/RAG/도구보다 바깥).
- `ChatClientConfig` — 주 `chatClient`·`plannerChatClient` 양쪽 `defaultAdvisors` 에 등록.
- **완료 기준**: 모든 LLM 호출에 요청/응답/토큰/지연 로그가 남는다. **검증 진행**: `compileJava` ✅, 실호출 로그 확인 ⬜.

#### Phase 4b — 메트릭(Micrometer) 🚧 코드 구현 완료 · 검증 대기
- `common/advisor/MetricsAdvisor` — `BaseAdvisor` 구현(로깅과 관심사 분리). 모든 `ChatClient` 호출의 지연·토큰을 Micrometer 미터로 기록:
  - `kyuloud.ai.chat.latency`(Timer) — 호출 지연, 태그 `model`.
  - `kyuloud.ai.chat.tokens`(DistributionSummary) — 토큰 수, 태그 `model`·`type=prompt|completion|total`.
- **의존성**: `micrometer-registry-prometheus`(runtimeOnly, Boot BOM 관리). actuator 노출 `health,info,metrics,prometheus`.
- `ChatClientConfig` — 주 `chatClient`·`plannerChatClient` `defaultAdvisors` 에 `MetricsAdvisor` 등록.
- **완료 기준**: `/actuator/metrics/kyuloud.ai.chat.latency`·`.../tokens` 와 `/actuator/prometheus` 에 메트릭 노출. **검증 진행**: `compileJava`/의존성 해석 ✅, 실호출 메트릭 확인 ⬜.

#### Phase 4c — 가드레일 Advisor 🚧 코드 구현 완료 · 검증 대기
- `common/advisor/GuardrailAdvisor` — `BaseAdvisor`, 가장 바깥(order = `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 110`)에서 입력 검사:
  - **차단**: 금칙어 포함 시 `GuardrailException`(→ 400 `GUARDRAIL_BLOCKED` 표준 에러) 으로 모델 호출 없이 차단.
  - **마스킹**: 사용자 메시지의 PII 마스킹 후 진행(가장 바깥이라 마스킹된 값만 로그·메모리·모델로 전달).
  - **출력 후처리(비파괴)**: 응답의 PII/금칙어를 경고 로깅만. 응답 재작성은 도구호출 중간응답 손상·스트리밍 경계분할 문제로 보류(탐지·관측에 집중).
- `common/guardrail/PiiMasker` — 이메일·전화·주민번호·카드번호 정규식 마스킹/탐지(순수 로직, 4e 단위테스트 용이).
- `common/exception/GuardrailException`(extends `BusinessException`), `config/GuardrailProperties`(`kyuloud.guardrail.{enabled,mask-pii,banned-words}`).
- `ChatClientConfig` — 양쪽 ChatClient `defaultAdvisors` 최상단에 등록.
- **완료 기준**: 금칙어 요청 → 400 차단, PII 포함 요청 → 마스킹되어 처리. **검증 진행**: `compileJava` ✅, 실호출 차단/마스킹 확인 ⬜.

#### Phase 4d — 대화 메모리 JDBC 영속화 ✅ 완료
- **의존성**: `spring-ai-starter-model-chat-memory-repository-jdbc`(BOM 2.0.0). 기존 pgvector용 PostgreSQL `DataSource` 를 재사용.
- `ChatMemoryConfig` — `new InMemoryChatMemoryRepository()` → 자동 구성된 `ChatMemoryRepository`(=`JdbcChatMemoryRepository`) 주입. `MessageWindowChatMemory`(max 20) 윈도우는 유지.
- **스키마**: `spring.ai.chat.memory.repository.jdbc.initialize-schema: always`(PostgreSQL 비임베디드라 always 필요, `SPRING_AI_CHAT_MEMORY` 테이블 자동 생성, 스크립트에 `IF NOT EXISTS` 포함이라 재기동 안전).
- **효과**: 재기동해도 `conversationId` 별 대화 맥락이 유지된다. Planner 임시 `planCid` 도 동일 저장소를 쓰되 실행 후 `ChatMemory.clear` 로 정리(누수 없음).
- **⚠️ conversationId 36자 제약**: `SPRING_AI_CHAT_MEMORY.conversation_id` 컬럼이 `VARCHAR(36)`(UUID 전제)이다. 36자를 넘는 conversationId 는 적재 시 `value too long for type character varying(36)` 오류가 난다. 따라서 ① Planner 임시 ID(`planCid`)는 접두사 없이 36자 UUID 만 사용, ② `ChatRequest.conversationId` 에 `@Size(max = 36)` 검증을 둬 긴 ID 는 DB 오류(500) 대신 400 으로 차단한다.
- **완료 기준**: 앱 재기동 후 동일 `conversationId` 로 이전 대화를 기억. **검증 완료** ✅: `compileJava`/의존성 해석 + 재기동 후 기억 유지(`/agent/chat`·`/agent/chat/plan` 둘 다, 36자 제약·plan 맥락 시드 수정 포함) 확인.

#### Phase 4e — 테스트 ⬜ (보류)
- 단위 테스트(도구·Planner·Advisor 로직) + `@SpringBootTest` 통합(모킹된 ChatModel/VectorStore) + RAG 정확도 평가 시나리오. 환경 독립적 회귀 안전망 확보.
- **보류 사유**: 대상 코드(도구/Planner/Advisor 등)를 먼저 수정할 계획이라, 테스트 작성은 그 이후로 미룸.

### Phase 5 — 평가 기반 에이전트 루프(Reflective Agent)
> **동기**: 기존 `planAndExecute`(3d-4)는 계획→실행→합성의 정적 파이프라인이라 **품질 게이트가 없다**(근거가 빈약해도 그대로 답함). 행동마다 충분성을 평가해 부족하면 추가 검색/다른 도구로 보완하는 Evaluator-Optimizer(Reflection) 루프를 도입한다.

#### Phase 5a — 평가 루프 🚧 코드 구현 완료 · 검증 대기
- **흐름**: 질문 파악·계획(`PlannerService`) → (행동 실행 → 평가 → 부족 시 추가/다른 행동)* → 최종 합성(`PlannerService.synthesize`).
- **행동 실행기 = 리서처 페르소나**(중요): loop 의 각 행동은 기본 에이전트 프롬프트(`system-agent.st`)가 아니라 전용 `system-loop-action.st` 로 실행한다. 풀 에이전트로 실행하면 첫 행동에서 도구를 여러 번 호출해 질문 전체를 답해버려 평가 루프가 1회에 끝나는 문제가 있다. 리서처는 *이번 한 행동만 조사·보고하고 최종 답은 쓰지 않으며*, 최종 답변은 `synthesize()` 에서만 만든다. → 행동이 원자화되어 검색→평가→추가검색 루프가 실제로 여러 회차 돈다. `agentSpec` 은 시스템 프롬프트를 파라미터로 받는 오버로드로 chat/plan(기본) 과 loop(리서처)를 분기.
- **평가자**: `agent/eval/EvaluatorService` — 도구·메모리 없는 보조 ChatClient(`plannerChatClient` 재사용)로 누적 근거의 충분성을 구조화 출력 `EvaluationVerdict{sufficient, missing, nextAction}` 으로 판정. 평가 실패 시 "충분" 폴백(무한루프 방지). 프롬프트 `system-evaluator.st`.
- **루프 제어**(`AgentService.loop`): 다음 행동은 남은 계획 단계 우선 → 소진 시 평가자 `nextAction`, 둘 다 없으면 조기 종료. 상한 `kyuloud.agent.loop.max-iterations`(기본 3). `missing` 은 다음 행동 프롬프트에 보완 지시로 전달.
- **대화 맥락**: plan 과 동일(임시 `loopCid` 에 실제 기록 시드 → 종료 시 중간 잡음 폐기 → 사용자 대화엔 원 질문→최종 답변 한 턴만 기록). seed/record 헬퍼는 plan/loop 공유.
- **API**: `POST /api/agent/loop` → `LoopResponse`(계획·회차별 행동/평가 기록·최종 답변·`toolsUsed`).
- **트레이드오프**: 평가마다 LLM 호출 추가(지연·비용↑), 자기평가 품질은 모델 의존(필요 시 평가용 모델 분리 검토).
- **완료 기준**: 빈약한 근거면 추가 검색을 거쳐 답하고, 충분하면 조기 종료. **검증 진행**: `compileJava` ✅, 실호출(평가→추가검색→답변) 확인 ⬜.

#### Phase 5b — 명확화(Clarification) 엔드포인트 🚧 코드 구현 완료 · 검증 대기
> **개념**: RAG(QuestionAnswerAdvisor)는 부족한 정보를 *문서에서* 채우지만, 명확화는 부족한 정보를 *사용자에게 되물어* 채우는 Human-in-the-loop 패턴(MCP 의 elicitation 과 같은 결). advisor 가 아니라 **답변 전 게이트** 단계이며, REST(stateless)라 호출 중 멈춰 묻지 않고 "되묻는 질문"을 응답으로 돌려준다. 사용자가 고른 답을 더해 같은 `conversationId` 로 답변 엔드포인트를 다시 호출한다.
- **판단기**: `agent/clarify/ClarificationService` — 도구·메모리 없는 보조 ChatClient(`plannerChatClient` 재사용)로 구조화 출력 `ClarificationVerdict{needsClarification, questions[]}` 생성. `ClarifyingQuestion{question, options[]}`. 프롬프트 `system-clarify.st`. 판단 실패/모순(되묻는다면서 질문 없음) 시 "되묻지 않음" 폴백.
- **대화 맥락**: `AgentService.clarify` 가 `chatMemory.get(cid)` 기록을 텍스트로 렌더해 함께 전달 → 이미 아는 정보는 되묻지 않음. 읽기 전용(메모리 미기록).
- **API**: `POST /api/agent/clarify` → `ClarifyResponse{request, needsClarification, questions[]}`. (독립 엔드포인트; 추후 chat/loop 선행 게이트로 흡수 가능)
- **완료 기준**: 모호한 질문 → 선택지 포함 되묻기 / 충분한 질문 → needsClarification=false. **검증 진행**: `compileJava` ✅, 실호출 확인 ⬜.

### Phase 6 — 통합 에이전트 (Workflow-scaffolded, 클린 재구현)
> **배경/설계 판단**: Anthropic "Building Effective Agents" 의 Routing · Orchestrator-workers · Evaluator-optimizer 를 통합한다. 설계를 가르는 핵심 축은 **모델 능력**이다 — *Agent-centric*(LLM 이 메타도구로 스스로 escalate/되묻기 판단; 강한 모델에 적합)은 로컬 4B급(`gemma4:e4b`)에선 판단 타이밍이 불안정해 깨지기 쉽다. 따라서 **Workflow-scaffolded** 로 간다: 각 단계가 결정적 코드 경로이고 약한 모델은 한 번에 좁은 판단 하나만 한다(구조화 출력 성공률↑, 예측·일관성↑).
>
> **Phase 3d-4(plan)·5(loop/clarify)는 "경험"으로 본다.** 누적된 구현(분산 엔드포인트, 메모리 seed/clear 트릭, ThreadLocal tracker, 보조 ChatClient 한 빈 재사용, RAG 이중 노출)을 재활용하지 않고, 교훈만 반영해 **단일 진입점으로 클린 재구현**한다. 기존 `/api/agent/*`·`/api/rag/chat` 은 deprecated 로 유지(검증/비교용), 신규는 **`POST /api/agent`** 하나.
>
> **흐름**:
> ```
> query → Router(분류) ─┬─ CLARIFY  → 되묻는 질문+선택지 반환(REST 왕복)
>                       ├─ DIRECT   → 단발 답변(+필요 시 RAG 검색)
>                       └─ RESEARCH → Orchestrator(동적 분해) → workers 병렬
>                                       → Evaluator-optimizer(부족 시 보강) → synthesize
> ```
> **마이그레이션 경로**: B 로 짜두면 추후 capable 모델 전환 시 Router 를 빼고 orchestrator/clarify 를 도구로 노출하면 Agent-centric(A)로 자연 수렴한다(= A 의 디리스크드 버전).

**클린 재구현 설계 원칙 (현재 누적 구현 대비)**
- **단일 진입점** `POST /api/agent` + 내부 Router 분기. (chat/plan/loop/clarify 4개 분산 → 1개)
- **`AgentContext` 명시적 주입**: `conversationId` · `evidence`(워커 중간결과) · `budget`(총 LLM/도구 호출·타임아웃) · `tracer` 를 코드가 들고 다닌다. → **메모리 seed/clear 트릭 폐기**: 중간 잡음을 임시 conversationId 로 DB 에 썼다 지우지 않고, `evidence` 리스트로 코드가 보유. 사용자 메모리엔 최종 한 턴만 기록.
- **수집형 tracer**: `ToolCallTracker` ThreadLocal(스트리밍/병렬에서 깨짐) → context 로 전달되는 수집형 tracer 로 교체(병렬·스트리밍 안전).
- **역할별 보조 클라이언트 의미 분리**: `routerClient`/`workerClient`/`evaluatorClient`(같은 모델이라도 역할·프롬프트 분리; `plannerChatClient` 단일 재사용의 의미 혼란 제거).
- **RAG 단일화**: advisor(`/rag/chat`) + tool(`RagSearchTool`) 이중 노출 → 워크플로에선 retriever 를 orchestrator/DIRECT 가 **직접 호출**(도구 노출 불필요)로 통일. **검색 시점 결정(#1=c)**: DIRECT 는 "검색이 필요한가"를 LLM 으로 판단하지 않고 **항상 저비용으로 1회 검색**하되 `similarityThreshold` 로 무관 결과를 걸러 빈 결과면 컨텍스트 주입을 생략한다(결정적·단순; "몇시야" 류는 threshold 에서 자연히 탈락).
- **전역 budget/정지조건**: 총 LLM 호출·도구 호출·타임아웃 상한을 `AgentContext` 가 강제(무한루프·비용 폭주 방지).
- **conversationId 규약**: 처음부터 UUID(≤36) 규약. 임시 작업 컨텍스트는 메모리(DB) 밖에서 처리해 `VARCHAR(36)`/잡음 적재 문제를 원천 차단.

**패턴 매핑**: Routing=진입 분류기(DIRECT/RESEARCH/CLARIFY, 약한 모델이 전략 하나만 선택) · Orchestrator-workers=RESEARCH 경로(동적 분해→가상스레드 병렬 워커→수집) · Evaluator-optimizer=orchestrator 결과를 감싸는 품질 루프(워커별이 아닌 **묶음 1회** 평가로 비용 절감).

**확정 결정 (PLAN 검토 반영)**
1. **DIRECT-RAG (#1=c)**: 위 RAG 단일화 참조 — 항상 저비용 검색 + threshold 필터(빈 결과면 미주입).
2. **Router 이중부담 (#2)**: Router 는 단일 LLM 호출 유지. 프롬프트에 "핵심 정보 부족으로 전략 선택 불가 시 CLARIFY" 가드 한 줄 + enum/예시를 타이트하게. 분류 robustness 는 6a 에서 실측. 오분류 폴백은 DIRECT(저비용·안전).
3. **tracer 메커니즘 (#3)**: Spring AI **`ToolContext`** 채택 — `.toolContext(Map)` 로 요청별 수집기를 넘기고 `@Tool` 메서드가 `ToolContext` 파라미터로 받아 기록. 병렬 워커는 각 호출이 자기 context map 을 가져 격리. **6a 에서 이 메커니즘을 먼저 PoC 검증**(6d 병렬의 선행 조건).
4. **budget 초과 동작 (#4)**: 상한 초과 시 에러가 아니라 **지금까지의 `evidence` 로 best-effort 합성 + 경고 로그**(부분 답변).
5. **CLARIFY 연속성 (#5)**: 통합 흐름에서 CLARIFY 로 분기하면 그 턴(원 질문 + 되묻기)을 **메모리에 기록**해, 사용자가 같은 `conversationId` 로 재호출할 때 맥락이 이어지게 한다(5b 독립 버전의 읽기전용과 다름).
6. **cross-cutting advisor (#6)**: 신규 `routerClient`/`workerClient`/`evaluatorClient` 에 Phase 4 의 **Guardrail·Logging·Metrics advisor 를 모두 적용**. 특히 Guardrail(입력 차단/PII)은 진입 클라이언트(router)에 필수.
7. **스트리밍 (#7)**: `/api/agent` 는 우선 **blocking** 으로 구현. 최종 synthesis 스트리밍은 추후(멀티스텝 중간은 비스트리밍).
8. **테스트 (#8)**: Phase 6 는 멀티스텝이라 회귀 위험이 커, 최소 **Router 분류 + orchestrator 분해**는 단위테스트를 함께 작성(보류 중인 4e 와 연결).

#### Phase 6a — 골격: 단일 엔드포인트 + Router + DIRECT ✅(코드, 실호출 검증 대기)
- `POST /api/agent` 신설. `RouterService`(구조화 출력 전략 enum, 오분류 폴백 DIRECT) + `AgentContext`(conversationId·budget·수집형 tracer) 기반 골격. 신규 클라이언트에 Guardrail/Logging/Metrics advisor 적용(#6).
- **DIRECT 경로만** 연결: 항상 저비용 RAG 1회 검색 + threshold 필터(#1=c) → 단발 답변. Router 가 RESEARCH/CLARIFY 로 분류해도 일단 DIRECT 로 폴백.
- **`ToolContext` tracer PoC(#3)**: `.toolContext(Map)` 로 요청별 수집기를 도구에 전달해 도구 호출이 기록되는지 검증(6d 병렬의 선행 조건).
- **완료 기준**: 단순 질문이 Router→DIRECT 로 흘러 답변. budget/정지조건·tracer(ToolContext) 동작, Guardrail 입력 차단 동작.
- **구현 메모**: 신규 패키지 `agent.unified` — `UnifiedAgentController`(`POST /api/agent`)·`UnifiedAgentService`·`RouterService`·`KnowledgeRetriever`(RAG 직접 검색, 도구 미노출)·`AgentContext`/`Budget`/`CallTracer`(수집형)·`RouteStrategy`/`RouteDecision`/`UnifiedAgentResponse`. 클라이언트 `routerChatClient`/`workerChatClient` 신설(메모리 advisor 없음 — 흐름이 history 를 `.messages()` 로 직접 주입하고 최종 한 턴만 기록 → seed/clear 트릭 폐기). 도구(`DateTimeTool`/`WebSearchTool`/`DocumentCatalogTool`/`RagSearchTool`)에 선택적 `ToolContext` 파라미터 추가 — 기존 ThreadLocal `ToolCallTracker`(구 엔드포인트용)와 신규 `CallTracer` 양쪽 기록(상호 무해). budget 설정: `kyuloud.agent.budget.{max-llm-calls,timeout-millis}`. 응답에 `routed`/`executed` 둘 다 노출(6a 는 항상 executed=DIRECT 폴백이라 분류 동작을 관찰 가능). **단위테스트(#8)는 4e 와 함께 보류**.

#### Phase 6b — CLARIFY 분기 ✅(코드, 실호출 검증 대기)
- Router 가 `CLARIFY` 로 분류 시(핵심 정보 부족으로 전략 선택 불가) 되묻는 질문+선택지 생성 → 응답(REST 왕복). 대화 맥락 반영해 이미 아는 정보는 안 물음.
- **연속성(#5)**: CLARIFY 턴(원 질문 + 되묻기)을 메모리에 기록해, 사용자가 같은 `conversationId` 로 재호출하면 맥락이 이어진다.
- **완료 기준**: 모호한 질문 → 되묻기 후 재호출 시 맥락 유지, 명확한 질문 → 해당 전략으로 진행.
- **구현 메모**: `UnifiedAgentService.tryClarify` — CLARIFY 생성은 5b 의 `ClarificationService`(전문가)를 **재사용**(중복 재구현 안 함; `plannerChatClient` 라 Guardrail/Logging/Metrics 이미 적용). Router(거친 게이트)가 CLARIFY 라도 전문가가 되물을 게 없으면 `null` 반환 → **DIRECT 폴백**(Router 과민 흡수). 되물을 게 있으면 `recordTurn(원질문 → 렌더링된 되묻기)` 으로 한 턴 기록(연속성). 응답에 `clarification`(구조화 질문+선택지) 필드 추가, `reply` 는 사람이 읽는 렌더링 텍스트. budget 에 clarify LLM 호출 1회 반영.

#### Phase 6c — RESEARCH: Orchestrator-workers (순차) ✅(코드, 실호출 검증 대기)
- `OrchestratorService` — 중앙 LLM 이 질문을 워커 하위작업으로 **동적 분해** → 각 워커(리서처 페르소나, 도구 사용, 근거만 보고)를 **순차** 실행 → `AgentContext.evidence` 누적 → `synthesize`.
- **완료 기준**: 다단계 질문이 RESEARCH 로 분기해 여러 워커 근거를 종합 답변.
- **구현 메모**: 신규 `OrchestratorService` + `WorkerPlan`/`WorkerTask`(구조화 출력) + 프롬프트 `system-orchestrator.st`(분해)·`system-worker.st`(리서처 페르소나, 근거만 보고). 역할별 클라이언트 분리 — 분해·합성은 도구 없는 reasoner(`plannerChatClient` 재사용)로 추론만, 워커 실행은 `workerChatClient`+DIRECT 동일 도구셋+요청별 `CallTracer`(ToolContext). 합성 프롬프트는 `system-synthesis.st` 재사용(폐기 예정 `PlannerService` 엔 의존 안 함 — 오케스트레이션은 클린 재구현). 워커는 목표별 RAG 1회 검색 컨텍스트 동봉. **정지조건(#4)**: 워커 루프가 `Budget.isExhausted()` 로 남은 워커 투입 차단(단 최소 1워커 보장 → 빈 근거 합성 방지), 합성은 소진돼도 best-effort 1회. 분해 실패/빈 계획은 단일 워커(원 질문) 폴백. MCP 도구는 `UnifiedAgentService` 의 단일 해석 지점에서 받아 워커에 전달(중복 해석 방지). 응답에 `evidence`(워커별 순서·목표·결과) 필드 추가 — `PlanResponse` 동형 투명성, DIRECT/CLARIFY 에선 빈 목록. **6d 병렬화 선행조건(CallTracer 격리)은 6a PoC 로 충족**. **단위테스트(#8)는 4e 와 함께 보류**.

#### Phase 6d — 워커 병렬화 ✅(코드, 실호출 검증 대기)
- 독립 하위작업을 가상스레드(`spring.threads.virtual.enabled=true`)로 **병렬** 실행. 수집형 tracer 로 병렬 도구추적 안전성 확보. 의존 관계 있는 작업은 순차 유지.
- **완료 기준**: 독립 워커 병렬 실행으로 지연 단축, 도구추적 정확.
- **구현 메모**: `WorkerTask` 에 `dependsOnPrevious` 플래그 추가(분해 시 모델이 의존성 표시; 불확실하면 false 권장 — 프롬프트 명시). `OrchestratorService.research` 가 order 순으로 훑으며 연속된 독립 작업(false)을 한 배치로 모아 `Executors.newVirtualThreadPerTaskExecutor()`(try-with-resources)로 **병렬** 실행하고, 의존 작업(true)을 만나면 직전 배치를 모두 마친 뒤(배리어) 단독 수행. 결과는 `Future` 를 제출 순서(=order)로 거둬 근거 순서 보존, 개별 워커 실패는 해당 근거만 생략(best-effort). 같은 요청 워커들이 하나의 `CallTracer`(내부 `CopyOnWriteArrayList`) 공유 → 병렬 도구추적 안전(6a PoC 메커니즘 실사용). budget 정지조건은 **배치 경계**에서 게이팅(워커당 정밀 차단 아님, 최소 1배치 보장). 명시적 가상스레드 executor 라 `spring.threads.virtual.enabled` 와 무관하게 동작(설정은 이미 켜져 있음). **단위테스트(#8)는 4e 와 함께 보류**.

#### Phase 6e — Evaluator-optimizer 통합 ⬜
- orchestrator 결과를 충분성 평가로 감싸 부족하면(missing) 워커를 추가 투입(보강 루프). budget 상한 내에서 반복.
- **완료 기준**: 빈약한 근거면 보강 후 답하고, 충분하면 조기 종료.

#### Phase 6f — 정리/통일 ⬜
- 기존 `/api/agent/chat|plan|loop|clarify`·`/api/rag/chat` deprecated 표기, RAG 단일화 마무리, 문서/Swagger 정리. (구 엔드포인트 제거는 신규 검증 후 별도 결정)
- **완료 기준**: `/api/agent` 단일 진입점으로 전 흐름 동작, 중복 경로 정리.

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
| 3 | POST | `/api/agent/plan` | Agent Plan-and-Execute (계획→실행→합성, 3d-4) |
| 5 | POST | `/api/agent/loop` | Agent 평가 루프 (행동→평가→추가행동→답변, 5a) *(Phase 6에서 통합 예정)* |
| 5 | POST | `/api/agent/clarify` | 명확화 — 답변 전 사용자에게 되물을 정보 판단 (5b) *(Phase 6에서 통합 예정)* |
| 6 | POST | `/api/agent` | **통합 에이전트** — Router→(CLARIFY/DIRECT/RESEARCH)→답변 (Phase 6) |

공통: 요청에 `conversationId`(세션 식별자)를 포함해 메모리/맥락을 관리한다.

> **Phase 6 통합 후**: `/api/agent` 단일 진입점이 권장 경로가 되고, 위 `/api/agent/chat|plan|loop|clarify`·`/api/rag/chat` 은 deprecated(검증·비교용으로 유지, 제거는 별도 결정).

---

## 7. 마일스톤 체크리스트

- [x] **M0** web/validation 의존성 추가, 프로파일·예외 처리, ping 엔드포인트 (✅ Phase 0)
- [x] **M1** Simple Chat (스트리밍 + 멀티턴 메모리 + 프롬프트 외부화) (✅ Phase 1)
- [x] **M2** RAG (문서 적재 + 검색 + 출처 응답) (✅ Phase 2a~2b 검증 완료)
- [x] **M3** Agent (tool-calling + RAG tool + ReAct 루프) (✅ Phase 3a~3c, 통합테스트 정상)
- [ ] **M4** 운영 강화 (가드레일/관측/영속화/테스트) — 4a~4d ✅(가드레일/관측/메트릭/영속화), 4e(테스트) 보류
- [ ] **M5** 통합 에이전트 (Router + Orchestrator-workers + Evaluator-optimizer, 단일 `/api/agent`) — Phase 6
  - 참고: Phase 3d-4(plan)·5(loop/clarify)는 "경험"으로 보고 Phase 6 에서 클린 재구현(구 엔드포인트는 deprecated).

---

## 8. 주요 설계 원칙

1. **점진적 확장**: 각 단계가 이전 단계를 재사용. Advisor·Tool로 기능을 "끼워넣는" 방식.
2. **모델 교체 용이성**: Ollama 옵션을 설정으로 분리해 모델(llama/qwen/gemma) 교체를 쉽게.
3. **로컬 우선**: 외부 클라우드 의존 없이 로컬에서 완결되도록 구성(데이터 프라이버시).
4. **관측 가능성**: 초기부터 로깅 Advisor를 넣어 프롬프트/토큰/지연을 추적.
5. **환각 억제**: RAG 단계부터 출처 명시 + "근거 없으면 모른다" 프롬프트 전략.
