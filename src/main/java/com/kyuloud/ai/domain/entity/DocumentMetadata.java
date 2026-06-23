package com.kyuloud.ai.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Phase 2b — 적재된 문서의 메타데이터(RDB).
 * 벡터 청크 자체는 pgvector 의 {@code vector_store} 테이블에 저장되며,
 * 여기서는 "무엇을/언제/몇 청크로" 적재했는지 추적한다.
 */
@Entity
@Table(name = "document_metadata")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private int chunks;

    @Column(nullable = false)
    private Instant createdAt;
}
