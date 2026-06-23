package com.kyuloud.ai.domain.repository;

import com.kyuloud.ai.domain.entity.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Phase 2b — 문서 메타데이터 저장소.
 */
public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {

    List<DocumentMetadata> findAllByOrderByIdDesc();
}
